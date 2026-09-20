"""스크립트 실행 엔진 (사용자 스크립트 탭의 런타임 코어).

실행 모델:
  script source --(exec)--> module ns
    @event("message") 함수들을 등록 -> ScriptableBot(iris.Bot) 생성 -> bot.run() thread

Kotlin(ScriptManager) 은 start()/stop()/status()/logs() 이 세 메서드만 알면 된다.
"""
from __future__ import annotations

import contextlib
import io
import os
import sys
import threading
import time
import traceback
import typing as t

from irisgui import bot as _botmod

_orig_stdout = sys.stdout
_orig_stderr = sys.stderr
from irisgui.bot import Bot as ScriptableBot, call_optional as _call_optional
from irisgui.manifest import Manifest, parse as parse_manifest


class _LogStream(io.TextIOBase):
    """sys.stdout/stderr 교체용.

    라인은 현재 스레드(thread-local 버퍼)로 라우팅한다. 핸들러는 iris 의
    ThreadPoolExecutor 에서 별개 스레드로 실행되므로, 스레드-local 이 비어있으면
    원래 스트림(logcat)으로 fallback 한다 — 다른 스레드의 print 가 유실되지 않게.
    """

    def __init__(self, level: str, fallback: t.TextIOBase):
        self._level = level
        self._fallback = fallback

    def write(self, text: str) -> int:  # noqa: D102
        if not text:
            return 0
        cur = _current_buffer()
        if cur is not None:
            cur.append(self._level, text)
        else:
            try:
                self._fallback.write(text)
            except Exception:
                pass
        return len(text)

    def flush(self) -> None:  # noqa: D102
        try:
            self._fallback.flush()
        except Exception:
            pass

    @property
    def writable(self) -> bool:  # noqa: D102
        return True


class Buffer:
    """스크립트별 로그 링버퍼 (Kotlin 은 logs() 로 회수)."""

    def __init__(self, capacity: int = 500):
        self._cap = capacity
        self._items: list[dict] = []
        self._lock = threading.Lock()

    def append(self, level: str, text: str) -> None:
        now = time.time()
        with self._lock:
            for part in text.rstrip("\n").split("\n"):
                if not part:
                    continue
                self._items.append({"timeMs": int(now * 1000), "level": level,
                                    "text": part})
            del self._items[:-self._cap]

    def snapshot(self, limit: int = 200) -> list[dict]:
        with self._lock:
            return [
                {"timeMs": i["timeMs"], "level": i["level"], "text": i["text"]}
                for i in reversed(self._items[-limit:])
            ]

    def clear(self) -> None:
        with self._lock:
            self._items.clear()


# 실행 중인 ScriptHost 버퍼를 전역 참조로 보존한다.
# handler 가 iris 의 ThreadPoolExecutor 별도 스레드에서 실행되어도 print/log가
# 올바른 버퍼로 라우팅되도록 thread-local 이 아닌 process-wide 참조로 한다.
class _Local(threading.local):
    stack: list["Buffer"] = []


_tls = _Local()


@contextlib.contextmanager
def _scope(buffer: "Buffer"):
    """thread-local buffer scope; prints/log within this thread 라우팅."""
    _tls.stack.append(buffer)
    try:
        yield
    finally:
        _tls.stack.pop()


def _current_buffer() -> t.Optional["Buffer"]:
    st = getattr(_tls, "stack", None)
    return st[-1] if st else None


class ScriptHost:
    def __init__(self, script_id: str, name: str, source: str, url: str,
                 max_workers: int | None = None):
        self.id = script_id
        self.name = name or script_id
        self.source = source
        self.url = url
        self._max_workers = max_workers
        self.buffer = Buffer()
        self.manifest: Manifest = parse_manifest(source)
        self.state = "idle"
        self.error: str | None = None
        self.started_at: float = 0.0
        self._bot: t.Optional[ScriptableBot] = None
        self._thread: t.Optional[threading.Thread] = None
        self._bot_driven = False
        self._stop = threading.Event()
        self._lock = threading.Lock()

    # ---- 상태 --------------------------------------------------------------
    def status(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "state": self.state,
            "error": self.error,
            "name_manifest": self.manifest.name,
            "requires": self.manifest.requires,
            "grants": self.manifest.grants,
            "uptimeSec": round(time.time() - self.started_at, 1) if self.started_at else 0,
            "threads": _thread_count(),
        }

    def logs(self, limit: int = 200) -> list[dict]:
        return self.buffer.snapshot(limit)

    # ---- 제어 --------------------------------------------------------------
    def start(self) -> None:
        with self._lock:
            if self.state in ("running", "starting"):
                return
            self.state = "starting"
            self.error = None
            self.started_at = time.time()
            self._stop.clear()
            self.buffer.append("INFO", f"=== {self.name} 실행 시작 (id={self.id}) ===")
            self._thread = threading.Thread(target=self._main, daemon=True)
            self._thread.start()

    def stop(self, timeout: float = 10.0) -> str:
        with self._lock:
            thread, state = self._thread, self.state
        if state in ("stopped", "idle"):
            return "stopped"
        self._stop.set()
        bot = self._bot
        if bot is not None:
            bot.request_stop()
        if thread is not None:
            thread.join(timeout)
            if thread.is_alive():
                self.buffer.append("WARN", "스크립트가 종료되지 않았습니다 (강제 종료)")
                self.state = "stopping"
                return "stopping"
        with self._lock:
            if self.state != "error":
                self.state = "stopped"
        self.buffer.append("INFO", f"=== {self.name} 종료 ===")
        return "stopped"

    # ---- internals ---------------------------------------------------------
    def _main(self) -> None:
        prev_out, prev_err = sys.stdout, sys.stderr
        sys.stdout = _LogStream("INFO", prev_out)
        sys.stderr = _LogStream("ERROR", prev_err)
        try:
            # 이 스레드는 자기 host 버퍼로 라우팅. emitter pool 스레드는 handler 를
            # 통해 각각 host 버퍼로 라우팅되므로 process-global 을 건드리지 않는다.
            with _scope(self.buffer):
                self._run_script()
        except BaseException as e:  # noqa: BLE001 - 스크립트 최상위 예외 전부 수합
            self._fail(e)
        finally:
            sys.stdout, sys.stderr = prev_out, prev_err

    def _wrap(self, fn: t.Callable) -> t.Callable:
        """handler 를 host 버퍼 scope 안에서 호출되게 포장."""
        def wrapped(*args, **kwargs):
            with _scope(self.buffer):
                return _call_optional(fn, *args)

        wrapped.__name__ = getattr(fn, "__name__", "<handler>")
        wrapped.__doc__ = getattr(fn, "__doc__", None)
        return wrapped

    def _run_script(self) -> None:
        # 런타임이 생성한 bot 을 모듈 전역에 바인딩한다.
        # 스크립트는 `from irisgui import event` 로 event 를 import 하더라도,
        # import 시점에는 bot 이 없으므로 런타임이 ns 에 바인딩한 `event` 를 사용한다.
        local: list[t.Callable] = []

        def _event(name: str) -> t.Callable:
            def deco(fn: t.Callable) -> t.Callable:
                fn._irisgui_event = str(name).lower()
                local.append(fn)
                return fn

            return deco

        ns: dict[str, t.Any] = {
            "__name__": f"script_{self.id}",
            "__file__": os.path.join(os.environ.get("HOME", "."), f"{self.id}.py"),
            "iris_url": self.url,
            "event": _event,
            "log": lambda msg: self.buffer.append("INFO", str(msg)),
        }
        bot = ScriptableBot(self.url, stop_event=self._stop,
                            max_workers=self._max_workers)
        self._bot = bot
        self._bot_driven = False

        def _run(*_a, **_k):  # ns["run"] / main() 에서 호출되는 공용 진입점
            if self._bot_driven:
                return
            self._bot_driven = True
            bot.run()
        ns["bot"] = bot
        ns["Bot"] = lambda *a, **k: bot
        ns["run"] = _run

        # `from irisgui import event` 로 등록하는 경로는 전역 저장소를 쓴다.
        # exec 동안 다른 스크립트와 등록 목록이 섞이지 않도록 직렬화한다.
        code = compile(self.source, f"{self.id}.py", "exec")
        with _import_lock:
            _botmod.clear_registered()
            exec(code, ns)  # noqa: S102 - 의도적으로 임의 스크립트 실행
            imported = [f for f in _botmod.registered()
                        if f not in local]
            _botmod.clear_registered()

        registered = local + imported
        for fn in registered:
            bot.emitter.register(getattr(fn, "_irisgui_event", "message"),
                                 self._wrap(fn))

        if not registered and not callable(ns.get("main")):
            raise ScriptError(
                "등록된 이벤트 핸들러(@event) 또는 main() 이 없습니다")

        if self.manifest.requires:
            missing = list(_missing(self.manifest.requires))
            if missing:
                self.buffer.append(
                    "WARN",
                    "요구 패키지 미설치: " + ", ".join(missing) +
                    " → 패키지 설치에서 설치하세요.",
                )

        self.state = "running"
        self.buffer.append("INFO", f"{len(registered)}개 핸들러 등록 — 이벤트 대기")

        # main() 이 bot.run()/bot_start() 로 기동했다면 _bot_drivenflag 이 set 된다.
        main = ns.get("main")
        if callable(main):
            _call_optional(main)
            if self._bot_driven:
                while not self._stop.is_set():
                    time.sleep(0.2)
                return
        if not self._stop.is_set():
            self._bot_driven = True
            bot.run()

    def _fail(self, exc: BaseException) -> None:
        self.error = f"{type(exc).__name__}: {exc}"
        self.state = "error"
        self.buffer.append("ERROR", self.error)
        buf = io.StringIO()
        traceback.print_exception(type(exc), exc, exc.__traceback__, file=buf)
        self.buffer.append("ERROR", buf.getvalue())


_import_lock = threading.Lock()


class ScriptError(Exception):
    pass


def _missing(reqs: list[str]) -> t.Iterator[str]:
    for r in reqs:
        mod = _module_of(r)
        try:
            __import__(mod)
        except Exception:
            yield r


def _module_of(requirement: str) -> str:
    import re

    m = re.match(r"([A-Za-z0-9_.\-]+)", requirement.strip())
    name = m.group(1) if m else requirement
    return name.replace("-", "_")


def _thread_count() -> int:
    return threading.active_count()

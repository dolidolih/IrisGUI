"""사용자 스크립트가 쓰는 Bot API.

iris.Bot 은 WebSocket recv 가 블로킹이며 종료 수단이 없으므로,
스크립트 런타임은 이 서브클래스에 cooperative stop 루프만 얹는다.
이벤트 해석/emit 은 전부 iris.Bot 에게 위임하므로 chat / message / new_member /
del_member / error 를 포함한 전 이벤트 타입을 그대로 사용할 수 있다.
"""
from __future__ import annotations

import inspect
import threading
import time
import traceback
import typing as t

from iris.bot import Bot as _IrisBot


# ---- 모듈 레벨 등록 슈거: @event("message") --------------------------------
_registered: list[t.Callable] = []


def event(name: str) -> t.Callable:
    """모듈 레벨에서 이벤트 핸들러 등록. 런타임이 bot.on(...) 으로 연결.

    `bot` 전역 변수 없이 스크립트를 쓸 수 있게 하는 슈거.
    """

    def deco(fn: t.Callable) -> t.Callable:
        fn._irisgui_event = name.lower()
        _registered.append(fn)
        return fn

    return deco


def registered() -> list[t.Callable]:
    """런타임: 모듈이 등록한 이벤트 핸들러 목록."""
    return _registered


def clear_registered() -> None:
    """런타임: 등록 목록 초기화 (스크립트별 유출 방지)."""
    _registered.clear()


class Bot(_IrisBot):
    """iris.Bot + cooperative stop. 그 외 동작은 iris.Bot 과 동일."""

    def __init__(
        self,
        iris_url: str,
        *,
        stop_event: threading.Event | None = None,
        max_workers: int | None = None,
    ):
        super().__init__(iris_url, max_workers=max_workers)
        # iris.Bot.bot_id 는 run() 에서만 set 되므로 dispatch 전 참조 시 오류.
        self.bot_id: int | None = None
        self._stop = stop_event or threading.Event()
        self._started = threading.Event()
        self._error: BaseException | None = None

    # ---- 등록 API ----------------------------------------------------------
    def on(self, name: str) -> t.Callable[[t.Callable], t.Callable]:
        """`@bot.on("message")` 등록. emitter.register 와 동일 semantics."""

        def deco(fn: t.Callable) -> t.Callable:
            self.emitter.register(name, fn)
            return fn

        return deco

    # ---- 상태 --------------------------------------------------------------
    def wait_started(self, timeout: float | None = None) -> bool:
        return self._started.wait(timeout)

    def request_stop(self) -> None:
        """런타임/UI 가 호출하는 cooperative stop 신호."""
        self._stop.set()

    @property
    def error(self) -> BaseException | None:
        return self._error

    # ---- 메인 루프 ---------------------------------------------------------
    def run(self) -> None:
        """stop_event 가 set 될 때까지 ws → emitter 로 이벤트를 계속 전달."""
        from websockets.sync.client import connect

        try:
            while not self._stop.is_set():
                try:
                    with connect(self.iris_ws_endpoint, close_timeout=0) as ws:
                        print("웹소켓에 연결되었습니다", flush=True)
                        self.bot_id = self.api.get_info()["bot_id"]
                        self._started.set()
                        # timeout 을 걸어 stop 을 polling 으로 반영한다.
                        while not self._stop.is_set():
                            try:
                                data = ws.recv(timeout=1.0)
                            except TimeoutError:
                                continue
                            self._process_frame(data)
                except Exception as e:
                    if self._stop.is_set():
                        break
                    print(f"웹소켓 연결 오류: {e}", flush=True)
                    print("3초 후 재연결합니다", flush=True)
                    self._started.set()
                    time.sleep(3)
        except BaseException as e:  # noqa: BLE001 - 최상위 수합
            self._error = e
            traceback.print_exc()
        finally:
            self._started.set()
            self._stop.set()

    def _process_frame(self, frame: str) -> None:
        """WS JSON 프레임 1건 → iris.Bot 내부의 정규 이벤트 처리 경로로 전달."""
        import json

        from iris.bot._internal.iris import IrisRequest

        data: dict = json.loads(frame)
        data["raw"] = data.get("json")
        if "json" in data:
            del data["json"]
        # iris.Bot.__process_iris_request: private 이라 name-mangled 로 호출.
        # 오버라이드가 아닌 재사용 — 이벤트 해석/emit 매핑을 그대로 따라간다.
        process = getattr(self, "_Bot__process_iris_request")
        process(IrisRequest(**data))


# ---- 스크립트용 convenience ------------------------------------------------
def run(bot: Bot) -> None:
    """`run()` 만 호출한 스크립트용: main 없이 블로킹."""
    bot.run()


def call_optional(fn: t.Callable, *args: t.Any) -> t.Any:
    """fn 의 허용 시그니처에 맞춰 args 를 넘긴다. main()/hook 대응용."""
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):  # builtins 등
        return fn(*args)
    params = sig.parameters.values()
    if any(p.kind == p.VAR_POSITIONAL for p in params):
        return fn(*args)
    n = len([p for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)])
    return fn(*args[:n])

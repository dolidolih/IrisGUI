"""Kotlin(ScriptManager) 이 호출하는 런타임 진입점.

Kotlin 은 이 모듈의 start/stop/status/logs 만 알면 된다.
Python 스크립트 소스 전문은 source 로 넘기고(파일 아님), 런타임은 exec 한다.
"""
from __future__ import annotations

import threading
import typing as t

from irisgui.engine import ScriptHost

_hosts: dict[str, ScriptHost] = {}
_lock = threading.Lock()

# UI/설정에서 참조할 기본 IRIS endpoint (http://127.0.0.1:<port>).
_default_url = "http://127.0.0.1:3000"


def configure(url: str = "", default_workers: int = 4) -> None:
    global _default_url, _workers
    if url:
        _default_url = url
    _workers = default_workers


_workers = 4


def start(script_id: str, name: str, source: str, url: str = "") -> dict:
    """실행 시작(또는 이미 실행 중이면 no-op + 상태)."""
    with _lock:
        host = _hosts.get(script_id)
        if host is not None and host.state in ("running", "starting"):
            return host.status()
        host = ScriptHost(script_id, name, source, url or _default_url,
                          max_workers=_workers)
        _hosts[script_id] = host
        host.start()
    return host.status()


def stop(script_id: str, timeout: float = 10.0) -> dict:
    with _lock:
        host = _hosts.get(script_id)
    if host is None:
        return {"id": script_id, "state": "notfound", "error": None}
    host.stop(timeout)
    return host.status()


def stop_all(timeout: float = 10.0) -> None:
    with _lock:
        hosts = list(_hosts.values())
    for h in hosts:
        try:
            h.stop(timeout)
        except Exception:  # noqa: BLE001
            pass


def status(script_id: str) -> t.Optional[dict]:
    with _lock:
        host = _hosts.get(script_id)
    return host.status() if host else None


def status_all() -> list[dict]:
    with _lock:
        hosts = list(_hosts.values())
    return [h.status() for h in hosts]


def logs(script_id: str, limit: int = 200) -> list[dict]:
    with _lock:
        host = _hosts.get(script_id)
    return host.logs(limit) if host else []

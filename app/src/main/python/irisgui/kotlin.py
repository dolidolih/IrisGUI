"""Kotlin(ScriptManager) 에서 호출하는 JSON-string API.

Chaquopy 의 PyObject.toString() 은 Python repr 이므로 dict 를 그대로 넘기면
Kotlin 에서 파싱하기 어렵다. 이 module 은 모든 반환을 JSON string 으로 한다.
"""
from __future__ import annotations

import json

from irisgui import manager


def start(script_id: str, name: str, source: str, url: str) -> str:
    return json.dumps(manager.start(script_id, name, source, url),
                      ensure_ascii=False, default=str)


def stop(script_id: str, timeout: float) -> str:
    return json.dumps(manager.stop(script_id, timeout),
                      ensure_ascii=False, default=str)


def stop_all(timeout: float) -> str:
    manager.stop_all(timeout)
    return "true"


def status(script_id: str) -> str:
    s = manager.status(script_id)
    return json.dumps(s, ensure_ascii=False, default=str) if s is not None else "null"


def status_all() -> str:
    return json.dumps(manager.status_all(), ensure_ascii=False, default=str)


def logs(script_id: str, limit: int) -> str:
    return json.dumps(manager.logs(script_id, limit),
                      ensure_ascii=False, default=str)


def configure(url: str, workers: int, libdir: str = "") -> str:
    manager.configure(url, workers, libdir)
    return "true"

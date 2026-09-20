"""probe — 파이썬 소스 트리(app/src/main/python)가 정상 패키징·import 되는지 검증하는 selftest용 모듈.

스크립트 런타임(ScriptRunner) 과는 무관한 pure sanity check. 추후 제거 예정.
"""
import importlib.util
import sys


def describe() -> str:
    deps = {}
    for m in ("requests", "websockets", "httpx", "PIL", "iris", "iris.bot", "iris.bot.models"):
        try:
            found = importlib.util.find_spec(m) is not None
        except Exception as e:  # noqa: BLE001 - probe
            found = f"ERR:{e}"
        deps[m] = found
    return (
        f"py={sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro} "
        f"deps={deps}"
    )

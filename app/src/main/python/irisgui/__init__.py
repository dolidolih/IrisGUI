"""IrisGUI 스크립트 런타임용 사용자 SDK.

스크립트는 ``from irisgui import Bot, event, run`` 만 import 하면 된다.
실제 구현(WebSocket·HTTP·DBObserver) 은 ``iris`` 패키지가 담당하고,
``irisgui`` 는 그 위에 스크립트 실행 통제(정지·grant·로그)를 얹은 얇은 래퍼다.
"""
from irisgui.bot import Bot, event
from irisgui.manifest import Manifest

__all__ = ["Bot", "event", "Manifest"]

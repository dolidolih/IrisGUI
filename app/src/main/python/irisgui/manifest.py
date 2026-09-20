"""스크립트 헤더 메타 데이터 parser.

스크립트 맨 앞 주석 블록에 `# irisgui:` 키: 값 형식으로 선언한다. Kotlin 은 스크립트를
실행하기 전에 이것을 읽어 grant/reqs 를 적용한다.

    # irisgui: name: 이미지 자동답장
    # irisgui: requires: requests, pillow
    # irisgui: grant: reply, query

`requires` 는 런타임 설치가 필요한 PyPI 요구사항. `grant` 는 런타임이 허용할 IRIS operation.
"""
from __future__ import annotations

import re
import typing as t

_KV = re.compile(r"^#\s*irisgui:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*(.*)$")

# 반복 가능한 키 (여러 라인을 합침). 나머지는 마지막 값 유지.
_MULTI = {"requires", "grant", "grants"}
_SPLIT = re.compile(r"[,\s]+")


class Manifest:
    def __init__(
        self,
        name: str = "",
        requires: t.Optional[list[str]] = None,
        grants: t.Optional[list[str]] = None,
        raw: t.Optional[dict] = None,
    ):
        self.name = name
        self.requires = requires or []
        self.grants = grants or []
        self.raw = raw or {}

    def requires_str(self) -> str:
        return " ".join(self.requires)

    def grants_str(self) -> str:
        return " ".join(self.grants)

    def __repr__(self) -> str:  # pragma: no cover
        return f"Manifest(name={self.name!r}, requires={self.requires}, grants={self.grants})"


def _split(value: t.Any) -> list[str]:
    if isinstance(value, list):
        out: list[str] = []
        for v in value:
            out.extend(p for p in _SPLIT.split(str(v).strip()) if p)
        return out
    return [p for p in _SPLIT.split(str(value).strip()) if p]


def parse(source: str) -> Manifest:
    """스크립트 소스 전문에서 `# irisgui:` 라인을 헤더 블록 한도에서 발췌.

    첫 비어있지 않은 코드 라인(주석/빈줄 아님) 이후 `# irisgui:` 는 무시한다.
    """
    raw: dict[str, str] = {}
    lists: dict[str, list[str]] = {}

    for line in source.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            m = _KV.match(stripped)
            if m:
                key = m.group(1).strip().lower()
                val = m.group(2).strip()
                if key in _MULTI:
                    key = "grants" if key == "grants" else key
                    lists.setdefault(key, [])
                    lists[key] += _split(val)
                else:
                    raw[key] = val
            continue
        break  # 코드 라인 시작 -> 헤더 끝

    requires = _split(lists.get("requires", []))
    grants = [g.lower() for g in _split(lists.get("grants", lists.get("grant", [])))]
    return Manifest(
        name=raw.get("name", ""),
        requires=requires,
        grants=grants,
        raw={**raw, **lists},
    )

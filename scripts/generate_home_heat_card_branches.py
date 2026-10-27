#!/usr/bin/env python3
"""Render data branches for the legacy-logic Heat check Home card."""

from pathlib import Path
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "home-heat-card-branches"
OUTPUT.mkdir(parents=True, exist_ok=True)

STYLE = """<style>text{font-family:'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif}</style>"""
ICON = """<rect x="36" y="34" width="36" height="36" rx="10" fill="#edf2e7"/>
<g transform="translate(43.5 40.5) scale(.86)" fill="none" stroke="#667b55" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3c1 5-4 5-4 9 0 2 2 3 3 3-1-3 3-4 3-7 4 4 6 7 4 10-2 4-9 4-12 0-3-5 3-10 6-15z"/></g>"""


def wrap(height: int, card_height: int, body: str, title: str) -> str:
    source = f"""<svg xmlns="http://www.w3.org/2000/svg" width="390" height="{height}" viewBox="0 0 390 {height}" role="img" aria-label="{title}">
<title>{title}</title>{STYLE}<rect width="390" height="{height}" fill="#f7f8f3"/>
<rect x="18.5" y="16.5" width="353" height="{card_height}" rx="18" fill="#fff" stroke="#dbe2d2"/>
{body}</svg>"""
    ET.fromstring(source)
    return source


def header(context: str, *, unit: bool = False) -> str:
    arrow = '<path d="m346 46 5 5-5 5" fill="none" stroke="#758470" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' if unit else ""
    return f"""{ICON}<text x="83" y="52" fill="#26372d" font-size="15" font-weight="500">查情</text>
<text x="83" y="69" fill="#768270" font-size="10.5">{context}</text>{arrow}"""


def footer() -> str:
    return """<path d="M19 214.5h352" stroke="#e8eddf"/>
<path d="M19 215h352v33a18 18 0 0 1-18 18H37a18 18 0 0 1-18-18z" fill="#f7f8f3"/>
<g transform="translate(35 232)" fill="none" stroke="#77866c" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"><path d="M7 13s5-4.5 5-8A5 5 0 0 0 2 5c0 3.5 5 8 5 8z"/><circle cx="7" cy="5" r="1.5"/></g>
<text x="55" y="244" fill="#697963" font-size="10.5">涉及 2 个单元</text>
<text x="286" y="244" fill="#26372d" font-size="10.5">查看任务</text>
<path d="m348 237 4 4-4 4" fill="none" stroke="#748171" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>"""


def overview_target_met() -> str:
    body = f"""{header('第 28 批 · 第 2/3 天')}
<text x="37" y="118" fill="#64725f" font-size="11">累计发情 / 目标发情</text>
<text x="36" y="168" fill="#26372d" font-size="42" font-weight="400">20</text>
<text x="85" y="166" fill="#748171" font-size="22">/ 20</text>
<rect x="37" y="188" width="316" height="6" rx="3" fill="#e9ede2"/>
<rect x="37" y="188" width="316" height="6" rx="3" fill="#66835b"/>{footer()}"""
    return wrap(284, 250, body, "查情总览：目标发情数已达到，任务仍进行中")


def overview_missing_target() -> str:
    body = f"""{header('第 28 批 · 第 2/3 天')}
<text x="37" y="118" fill="#64725f" font-size="11">累计发情</text>
<text x="36" y="168" fill="#26372d" font-size="42" font-weight="400">8</text>
<text x="64" y="166" fill="#748171" font-size="18">头</text>
<text x="37" y="191" fill="#8a6b37" font-size="11">目标发情数待核对</text>{footer()}"""
    return wrap(284, 250, body, "查情总览：目标数不可用")


def unit_with_signs() -> str:
    body = f"""{header('第 28 批 · 单元 6 · 第 2/3 天', unit=True)}
<text x="37" y="116" fill="#64725f" font-size="11">该单元</text>
<text x="36" y="164" fill="#26372d" font-size="42" font-weight="400">12</text>
<text x="83" y="161" fill="#26372d" font-size="14">头尚未确认发情</text>
<text x="37" y="191" fill="#697963" font-size="11">其中 3 头有发情迹象</text>"""
    return wrap(232, 198, body, "查情单元卡：有发情迹象")


def main() -> None:
    variants = {
        "overview-target-met.svg": overview_target_met(),
        "overview-missing-target.svg": overview_missing_target(),
        "unit-with-signs.svg": unit_with_signs(),
    }
    for filename, source in variants.items():
        (OUTPUT / filename).write_text(source + "\n", encoding="utf-8")
    print(f"Generated {len(variants)} Heat check branches in {OUTPUT}")


if __name__ == "__main__":
    main()

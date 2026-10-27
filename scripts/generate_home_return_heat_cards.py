#!/usr/bin/env python3
"""Draw return-heat Home review fixtures from the legacy Home data fields."""

from pathlib import Path
from math import cos, pi, sin
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "home-return-heat-card-branches"
OUTPUT.mkdir(parents=True, exist_ok=True)
ALIGNMENT_OUTPUT = ROOT / "docs" / "home-return-heat-feature-alignment"
ALIGNMENT_OUTPUT.mkdir(parents=True, exist_ok=True)

STYLE = """<style>text{font-family:'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif}</style>"""
ICON = """<rect x="36" y="34" width="36" height="36" rx="10" fill="#fff1e8"/>
<g transform="translate(45 42) scale(1.1)" fill="none" stroke="#c7733b" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
<path d="M7 14s-5.5-3.4-5.5-7.4C1.5 4 3 2.7 4.8 2.7c1.1 0 1.8.5 2.2 1.1.4-.6 1.2-1.1 2.2-1.1 1.8 0 3.3 1.3 3.3 3.9"/>
<path d="M10 8a4 4 0 0 1 5 1.8M15 8v1.8h-1.8M14.5 12a4 4 0 0 1-5 1.4M9.5 15v-1.6h1.7"/>
</g>"""


def wrap(height: int, card_height: int, body: str, title: str) -> str:
    source = f"""<svg xmlns="http://www.w3.org/2000/svg" width="390" height="{height}" viewBox="0 0 390 {height}" role="img" aria-label="{title}">
<title>{title}</title>{STYLE}<rect width="390" height="{height}" fill="#f7f8f3"/>
<rect x="18.5" y="16.5" width="353" height="{card_height}" rx="18" fill="#fff" stroke="#dbe2d2"/>
{body}</svg>"""
    ET.fromstring(source)
    return source


def header(context: str, *, unit: bool = False) -> str:
    arrow = '<path d="m346 46 5 5-5 5" fill="none" stroke="#758470" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>' if unit else ""
    return f"""{ICON}<text x="83" y="52" fill="#26372d" font-size="15" font-weight="500">返情检查</text>
<text x="83" y="69" fill="#768270" font-size="10.5">{context}</text>{arrow}"""


def footer(top: int) -> str:
    return f"""<path d="M19 {top - .5}h352" stroke="#e8eddf"/>
<path d="M19 {top}h352v33a18 18 0 0 1-18 18H37a18 18 0 0 1-18-18z" fill="#f7f8f3"/>
<g transform="translate(35 {top + 17})" fill="none" stroke="#77866c" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"><path d="M7 13s5-4.5 5-8A5 5 0 0 0 2 5c0 3.5 5 8 5 8z"/><circle cx="7" cy="5" r="1.5"/></g>
<text x="55" y="{top + 29}" fill="#697963" font-size="10.5">涉及 2 个单元</text>
<text x="286" y="{top + 29}" fill="#26372d" font-size="10.5">查看任务</text>
<path d="m348 {top + 22} 4 4-4 4" fill="none" stroke="#748171" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>"""


def pie(cx: int, cy: int, checked: int, total: int, date: str) -> str:
    """One dated snapshot; the slice is checked / that snapshot's denominator."""
    radius = 8
    fraction = checked / total
    circle = f'<circle cx="{cx}" cy="{cy}" r="{radius}" fill="#e4e9e1"/>'
    if fraction >= 1:
        slice_shape = f'<circle cx="{cx}" cy="{cy}" r="{radius}" fill="#66835b"/>'
    elif fraction <= 0:
        slice_shape = ""
    else:
        angle = -pi / 2 + 2 * pi * fraction
        x = cx + radius * cos(angle)
        y = cy + radius * sin(angle)
        large_arc = 1 if fraction > 0.5 else 0
        slice_shape = (
            f'<path d="M {cx} {cy} L {cx} {cy - radius} '
            f'A {radius} {radius} 0 {large_arc} 1 {x:.3f} {y:.3f} Z" fill="#66835b"/>'
        )
    return f'<g><title>{date}：检查时间落在当日 {checked}/{total} 头</title>{circle}{slice_shape}</g>'


def daily_pies() -> str:
    # Five dated sample rows in this fixture; each point keeps its own ratio.
    rows = (("10月10日", 20, 20), ("10月11日", 18, 20), ("10月12日", 12, 20),
            ("10月13日", 20, 20), ("10月14日", 6, 20))
    pies = "".join(
        pie(240 + index * 26, 225, checked, total, date)
        for index, (date, checked, total) in enumerate(rows)
    )
    return (
        '<text x="37" y="229" fill="#64725f" font-size="11">近 5 个统计日检查快照</text>'
        + pies
        + '<text x="37" y="252" fill="#697963" font-size="10.5">最近统计：10 月 14 日 · 6/20 头</text>'
    )


def overview(returned: int, cohort: int, *, stats: bool, title: str) -> str:
    other = cohort - returned
    top = 275 if stats else 215
    card_height = top + 51 - 16
    height = card_height + 34
    body = f"""{header('第 25 批 · 第 3/7 天')}
<text x="37" y="115" fill="#64725f" font-size="11">已记录返情</text>
<text x="36" y="165" fill="#a65d31" font-size="42" font-weight="400">{returned}</text>
<text x="{66 if returned < 10 else 85}" y="162" fill="#697963" font-size="14">头</text>
<text x="37" y="188" fill="#697963" font-size="11">另有 {other} 头尚无返情记录 · 有配种时间 {cohort} 头</text>
{daily_pies() if stats else ''}{footer(top)}"""
    return wrap(height, card_height, body, title)


def overview_no_cohort() -> str:
    body = f"""{header('第 25 批 · 第 3/7 天')}
<text x="37" y="123" fill="#26372d" font-size="15" font-weight="500">暂无可核对的配种时间</text>
<text x="37" y="151" fill="#697963" font-size="11">返情预计日期暂不可用</text>{footer(215)}"""
    return wrap(284, 250, body, "返情总览：没有可用配种时间")


def unit_due_signs() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 第 3/7 天', unit=True)}
<text x="37" y="116" fill="#64725f" font-size="11">该单元</text>
<text x="36" y="164" fill="#26372d" font-size="42" font-weight="400">8</text>
<text x="66" y="161" fill="#26372d" font-size="14">头已到预计返情日</text>
<text x="37" y="193" fill="#a65d31" font-size="11">有返情迹象记录 2 头</text>"""
    return wrap(232, 198, body, "返情单元：预计返情日已到且有迹象记录")


def unit_signs_only() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 第 3/7 天', unit=True)}
<text x="37" y="116" fill="#64725f" font-size="11">该单元</text>
<text x="36" y="164" fill="#a65d31" font-size="42" font-weight="400">2</text>
<text x="66" y="161" fill="#26372d" font-size="14">头有返情迹象记录</text>
<text x="37" y="193" fill="#697963" font-size="11">预计返情日尚未到</text>"""
    return wrap(232, 198, body, "返情单元：仅有迹象记录")


def unit_waiting() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 第 3/7 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="15" font-weight="500">本单元尚未到预计返情日</text>
<text x="37" y="148" fill="#697963" font-size="11">最近一头：10 月 15 日</text>"""
    return wrap(190, 156, body, "返情单元：预计返情日未到")


def unit_missing_time() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 第 3/7 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="15" font-weight="500">预计返情时间待核对</text>
<text x="37" y="148" fill="#697963" font-size="11">2 头缺少可用配种时间</text>"""
    return wrap(190, 156, body, "返情单元：配种时间缺失")


def aligned_overview_due() -> str:
    body = f"""{header('第 25 批 · 任务第 3/7 天')}
<text x="37" y="121" fill="#26372d" font-size="42" font-weight="400">38</text>
<text x="90" y="118" fill="#26372d" font-size="15">头待本次观察</text>
<text x="37" y="152" fill="#697963" font-size="11">当前观察时段</text>{footer(179)}"""
    return wrap(248, 214, body, "返情首页对齐提案：总览当前观察待处理")


def aligned_unit_due() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 任务第 3/7 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="42" font-weight="400">15</text>
<text x="91" y="118" fill="#26372d" font-size="15">头待本次观察</text>
<text x="37" y="152" fill="#697963" font-size="11">只统计本单元</text>"""
    return wrap(190, 156, body, "返情首页对齐提案：单元当前观察待处理")


def aligned_waiting() -> str:
    body = f"""{header('第 25 批 · 单元 6 · 任务第 3/7 天', unit=True)}
<text x="37" y="119" fill="#26372d" font-size="15" font-weight="500">下一次观察 · 14:30</text>
<text x="37" y="148" fill="#697963" font-size="11">本单元当前无待处理观察</text>"""
    return wrap(190, 156, body, "返情首页对齐提案：单元等待下一次观察")


def aligned_unverified() -> str:
    body = f"""{header('第 25 批 · 任务第 3/7 天')}
<text x="37" y="122" fill="#26372d" font-size="15" font-weight="500">返情检查进行中</text>
<text x="37" y="150" fill="#697963" font-size="11">查看任务中的观察名单</text>{footer(179)}"""
    return wrap(248, 214, body, "返情首页对齐提案：待观察人数无可靠口径时的保守卡")


def main() -> None:
    variants = {
        "overview-with-stats.svg": overview(2, 20, stats=True, title="返情总览：返情 2 头，另 18 头尚无返情记录，有每日检查统计"),
        "overview-no-stats.svg": overview(2, 20, stats=False, title="返情总览：有返情结果但没有每日检查统计"),
        "overview-all-returned.svg": overview(20, 20, stats=True, title="返情总览：20 头均已记录返情，任务仍进行中"),
        "overview-no-cohort.svg": overview_no_cohort(),
        "unit-due-signs.svg": unit_due_signs(),
        "unit-signs-only.svg": unit_signs_only(),
        "unit-waiting.svg": unit_waiting(),
        "unit-missing-time.svg": unit_missing_time(),
    }
    for filename, source in variants.items():
        (OUTPUT / filename).write_text(source + "\n", encoding="utf-8")
    aligned = {
        "overview-due.svg": aligned_overview_due(),
        "unit-due.svg": aligned_unit_due(),
        "unit-waiting.svg": aligned_waiting(),
        "overview-unverified.svg": aligned_unverified(),
    }
    for filename, source in aligned.items():
        (ALIGNMENT_OUTPUT / filename).write_text(source + "\n", encoding="utf-8")
    print(f"Generated {len(variants)} legacy-led cards and {len(aligned)} feature-aligned proposals")


if __name__ == "__main__":
    main()

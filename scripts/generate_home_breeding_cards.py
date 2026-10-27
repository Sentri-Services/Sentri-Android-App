#!/usr/bin/env python3
"""Draw breeding Home card review fixtures from the old Home data model."""

from pathlib import Path
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "home-breeding-card-branches"
OUTPUT.mkdir(parents=True, exist_ok=True)

STYLE = """<style>text{font-family:'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif}</style>"""
ICON = """<rect x="36" y="34" width="36" height="36" rx="10" fill="#eaf2ff"/>
<g transform="translate(45 43) scale(1.1)" fill="#2f7bff">
<path d="M7.2572.1719C3.6858.1719.7715 3.0576.7715 6.6576s2.9143 6.4571 6.4857 6.4571c1.7429 0 3.3143-.6857 4.4857-1.8-.2-.0857-.4-.1714-.6-.3143-1.1143-.6571-1.7714-1.6285-1.4857-2.2.2857-.5714 1.4286-.4857 2.5143.1714.3143.1714.6.4.8286.6286.4571-.8857.7142-1.9143.7142-2.9714C13.7429 3.0576 10.8286.1719 7.2572.1719ZM8.9715 3.829c.3428.6572-.1143 1.6572-1.0572 2.2-.9428.5429-1.9714.4572-2.3428-.2286-.2286-.4285-.1143-1.0285.2857-1.5428.4-.5143 1.0572-.8857 1.6857-.9714.6572-.0857 1.2.1143 1.4286.5428ZM14.4 10.8576c-.1428-.2857-.4857-.4286-.7714-.2572-.2857.1429-.4.5143-.2571.8.5428 1.1429.6285 1.9143.3428 2.3715-.0857.1428-.2857.1428-.7428-.0286l-1.0572-.3714c-.3428-.1143-.5714-.1715-.8571-.1715-.8572 0-1.6286.5429-2.3714 1.5715-.2.2571-.1429.6571.1142.8571.2572.1714.6286.1143.8-.1429.5429-.7428 1-1.0857 1.4286-1.0857.1429 0 .2571.0286.3429.0572l.8.3143c1.2571.4857 1.9714.5428 2.5143-.3143.5714-.8572.4285-2.0572-.2858-3.5429Z"/>
<path d="M8.0858 8.171c-.6 1.0857.2857 2.7143 1.9714 3.6572 1.6857.9428 3.5428.8286 4.1143-.2572.6-1.0857-.2857-2.7143-1.9715-3.6571-1.6571-.9429-3.5142-.8286-4.1142.2571Zm3.5714.7715c1.2.6571 1.7428 1.6857 1.5143 2.0571-.2286.4-1.3429.4572-2.5429-.2-1.2-.6571-1.7429-1.6571-1.5143-2.0571.2286-.4 1.3429-.4572 2.5429.2Z"/>
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
    return f"""{ICON}<text x="83" y="52" fill="#26372d" font-size="15" font-weight="500">配种</text>
<text x="83" y="69" fill="#768270" font-size="10.5">{context}</text>{arrow}"""


def footer() -> str:
    return """<path d="M19 214.5h352" stroke="#e8eddf"/>
<path d="M19 215h352v33a18 18 0 0 1-18 18H37a18 18 0 0 1-18-18z" fill="#f7f8f3"/>
<g transform="translate(35 232)" fill="none" stroke="#77866c" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"><path d="M7 13s5-4.5 5-8A5 5 0 0 0 2 5c0 3.5 5 8 5 8z"/><circle cx="7" cy="5" r="1.5"/></g>
<text x="55" y="244" fill="#697963" font-size="10.5">涉及 2 个单元</text>
<text x="286" y="244" fill="#26372d" font-size="10.5">查看任务</text>
<path d="m348 237 4 4-4 4" fill="none" stroke="#748171" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>"""


def overview_recorded(count: int, cohort: int, title: str) -> str:
    width = round(316 * min(count / cohort, 1))
    body = f"""{header('第 28 批 · 第 2/3 天')}
<text x="37" y="118" fill="#64725f" font-size="11">已配种（至少 1 次）/ 已标发情</text>
<text x="36" y="168" fill="#26372d" font-size="42" font-weight="400">{count}</text>
<text x="{85 if count >= 10 else 66}" y="166" fill="#748171" font-size="22">/ {cohort}</text>
<rect x="37" y="188" width="316" height="6" rx="3" fill="#e9ede2"/>
<rect x="37" y="188" width="{width}" height="6" rx="3" fill="#66835b"/>{footer()}"""
    return wrap(284, 250, body, title)


def overview_no_heat() -> str:
    body = f"""{header('第 28 批 · 第 2/3 天')}
<text x="37" y="123" fill="#26372d" font-size="15" font-weight="500">暂无已标发情母猪</text>
<text x="37" y="151" fill="#697963" font-size="11">配种任务仍进行中</text>{footer()}"""
    return wrap(284, 250, body, "配种总览：暂无已标发情母猪")


def unit_mixed() -> str:
    body = f"""{header('第 28 批 · 单元 6 · 第 2/3 天', unit=True)}
<text x="37" y="116" fill="#64725f" font-size="11">该单元</text>
<text x="36" y="164" fill="#26372d" font-size="42" font-weight="400">4</text>
<text x="66" y="161" fill="#26372d" font-size="14">头可配种</text>
<text x="37" y="187" fill="#697963" font-size="11">第 1 次 1 头 · 第 2 次 3 头</text>
<text x="37" y="207" fill="#697963" font-size="11">另有 3 头未到配种间隔</text>"""
    return wrap(248, 214, body, "配种单元：首次及再次配种同时可做")


def unit_waiting() -> str:
    body = f"""{header('第 28 批 · 单元 6 · 第 2/3 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="15" font-weight="500">当前无可配种母猪</text>
<text x="37" y="148" fill="#697963" font-size="11">3 头未到间隔 · 最近 14:00 可配</text>"""
    return wrap(190, 156, body, "配种单元：尚未到配种间隔")


def unit_sequence_done() -> str:
    body = f"""{header('第 28 批 · 单元 6 · 第 2/3 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="15" font-weight="500">本单元已达配置配种次数</text>
<text x="37" y="148" fill="#697963" font-size="11">6 头 · 整项任务仍进行中</text>"""
    return wrap(190, 156, body, "配种单元：该单元已达配置次数")


def unit_no_heat() -> str:
    body = f"""{header('第 28 批 · 单元 6 · 第 2/3 天', unit=True)}
<text x="37" y="121" fill="#26372d" font-size="15" font-weight="500">本单元暂无已标发情母猪</text>"""
    return wrap(172, 138, body, "配种单元：暂无已标发情母猪")


def main() -> None:
    variants = {
        "overview-recorded.svg": overview_recorded(8, 20, "配种总览：至少配种一次 8 头，已标发情 20 头"),
        "overview-all-recorded.svg": overview_recorded(20, 20, "配种总览：已标发情母猪均有配种记录，任务仍进行中"),
        "overview-no-heat.svg": overview_no_heat(),
        "unit-mixed.svg": unit_mixed(),
        "unit-waiting.svg": unit_waiting(),
        "unit-sequence-done.svg": unit_sequence_done(),
        "unit-no-heat.svg": unit_no_heat(),
    }
    for filename, source in variants.items():
        (OUTPUT / filename).write_text(source + "\n", encoding="utf-8")
    print(f"Generated {len(variants)} breeding card examples in {OUTPUT}")


if __name__ == "__main__":
    main()

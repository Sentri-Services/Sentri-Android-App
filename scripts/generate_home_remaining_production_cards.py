#!/usr/bin/env python3
"""Render review fixtures for the remaining production Home cards.

These are examples for docs/home-remaining-production-cards.md, not app data or
farm scheduling rules. Keep text and branch selection in the accompanying spec.
"""

from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape


OUT = Path(__file__).resolve().parents[1] / "docs/home-remaining-production-cards"


@dataclass(frozen=True)
class Card:
    name: str
    title: str
    scope: str
    timing: str
    main: str
    detail: str = ""
    progress: str = ""
    done: int = 0
    total: int = 0
    unit: bool = False
    tone: str = "normal"


CARDS = [
    Card("pregnancy-due", "孕检", "第 28 批", "任务第 2/3 天", "5 头待孕检", "其中复查 2 头", "本次检查已记录 8/20 头", 8, 20),
    Card("pregnancy-overdue", "孕检", "第 28 批", "进行中", "5 头待孕检", "其中 3 头已过检查期限", "本次检查已记录 8/20 头", 8, 20, tone="urgent"),
    Card("pregnancy-next", "孕检", "第 28 批 · 单元 6", "进行中", "下次孕检 10 月 17 日", "本单元当前没有到期检查", "本次检查已记录 4/9 头", 4, 9, True),
    Card("pregnancy-unknown", "孕检", "第 28 批 · 单元 6", "进行中", "检查信息待核对", "进入任务查看对象与安排", unit=True),
    Card("farrowing-live", "分娩", "第 28 批", "进行中", "2 头正在分娩", "另有 3 头预产日已过", "已分娩 7/18 头", 7, 18),
    Card("farrowing-past-due", "分娩", "第 28 批 · 单元 6", "进行中", "3 头预产日已过", "尚无分娩记录", "已分娩 4/9 头", 4, 9, True, "urgent"),
    Card("farrowing-today", "分娩", "第 28 批 · 单元 6", "进行中", "4 头今天预产", "尚无分娩记录", "已分娩 4/9 头", 4, 9, True),
    Card("farrowing-next", "分娩", "第 28 批 · 单元 6", "进行中", "下次预产 10 月 17 日", "当前没有已记录的在产母猪", "已分娩 4/9 头", 4, 9, True),
    Card("farrowing-unknown", "分娩", "第 28 批 · 单元 6", "进行中", "预产时间待核对", "缺有效配种时间或计划日数", "已分娩 4/9 头", 4, 9, True),
    Card("postpartum-due", "产后检查", "第 28 批", "进行中", "3 头待产后检查", "其中 1 头已过检查期限", "产后检查已记录 6/9 头", 6, 9, tone="urgent"),
    Card("postpartum-recheck", "产后检查", "第 28 批 · 单元 6", "进行中", "2 头待产后检查", "其中明确安排复查 1 头", "产后检查已记录 4/7 头", 4, 7, True),
    Card("postpartum-next", "产后检查", "第 28 批 · 单元 6", "进行中", "下次检查 10 月 17 日", "本单元当前没有到期检查", "产后检查已记录 4/7 头", 4, 7, True),
    Card("postpartum-wait", "产后检查", "第 28 批", "进行中", "等待分娩记录", "暂无可计算的产后检查时间"),
    Card("piglet-due", "仔猪处理", "第 28 批", "进行中", "4 窝今天有护理要做", "其中 2 窝有两项待做", "全计划必做项已完成 1/8 窝", 1, 8),
    Card("piglet-overdue", "仔猪处理", "第 28 批 · 单元 6", "进行中", "3 窝有护理未完成", "其中 2 窝已过计划窗口", "全计划必做项已完成 1/5 窝", 1, 5, True, "urgent"),
    Card("piglet-next", "仔猪处理", "第 28 批 · 单元 6", "进行中", "下次护理 10 月 17 日", "今天没有到期护理", "全计划必做项已完成 1/5 窝", 1, 5, True),
    Card("piglet-wait", "仔猪处理", "第 28 批", "进行中", "等待新窝进入护理计划", "任务仍在进行中"),
    Card("piglet-unit-done", "仔猪处理", "第 28 批 · 单元 6", "进行中", "本单元计划护理已做完", "整项任务仍在进行中", "全计划必做项已完成 5/5 窝", 5, 5, True),
    Card("weaning-due", "断奶检查", "第 28 批", "进行中", "3 窝待断奶检查", "今天进入检查窗口", "断奶检查已记录 5/8 窝", 5, 8),
    Card("weaning-overdue", "断奶检查", "第 28 批 · 单元 6", "进行中", "2 窝待断奶检查", "已过配置的检查期限", "断奶检查已记录 3/5 窝", 3, 5, True, "urgent"),
    Card("weaning-next", "断奶检查", "第 28 批 · 单元 6", "进行中", "下次检查 10 月 17 日", "本单元当前没有到期检查", "断奶检查已记录 3/5 窝", 3, 5, True),
    Card("weaning-unknown", "断奶检查", "第 28 批", "进行中", "检查安排待核对", "当前不能确认到期窝数"),
    Card("nursery-pending", "结束保育检查", "第 28 批", "进行中", "3 栏待结束保育检查", "按栏查看并记录检查结果", "已检查 5/8 栏", 5, 8),
    Card("nursery-unit-done", "结束保育检查", "第 28 批 · 单元 6", "进行中", "本单元已检查完", "整项任务仍在进行中", "已检查 4/4 栏", 4, 4, True),
    Card("nursery-unknown", "结束保育检查", "第 28 批", "进行中", "检查栏位待核对", "进入任务查看栏位名单"),
]


def draw(c: Card) -> str:
    h = 248 if c.progress else 219
    ink = "#9d3f2f" if c.tone == "urgent" else "#26372d"
    bar = ""
    if c.progress:
        fraction = max(0, min(1, c.done / c.total)) if c.total else 0
        bar = (f'<text x="37" y="176" fill="#64725f" font-size="11">{escape(c.progress)}</text>'
               '<rect x="37" y="191" width="316" height="6" rx="3" fill="#e9ede2"/>'
               f'<rect x="37" y="191" width="{316 * fraction:.1f}" height="6" rx="3" fill="#66835b"/>')
    footer = ('<path d="M19 215h352" stroke="#e8eddf"/>'
              '<path d="M19 215h352v33a18 18 0 0 1-18 18H37a18 18 0 0 1-18-18z" fill="#f7f8f3"/>'
              '<text x="37" y="242" fill="#697963" font-size="11">涉及 2 个单元</text>'
              '<text x="285" y="242" fill="#26372d" font-size="11">查看任务  ›</text>') if not c.unit else ""
    # Overview cards are 266px tall. Unit cards have no footer and use the header arrow.
    card_h = 250 if not c.unit else (208 if c.progress else 181)
    svg_h = card_h + 34
    bottom = footer if not c.unit else ""
    arrow = '<text x="343" y="55" fill="#6d7c67" font-size="19">›</text>' if c.unit else ""
    main_size = 26 if len(c.main) < 14 else 21
    source = f'''<svg xmlns="http://www.w3.org/2000/svg" width="390" height="{svg_h}" viewBox="0 0 390 {svg_h}" role="img" aria-label="{escape(c.title + '：' + c.main)}">
<title>{escape(c.title + '：' + c.main)}</title>
<style>text{{font-family:'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif}}</style>
<rect width="390" height="{svg_h}" fill="#f7f8f3"/>
<rect x="18.5" y="16.5" width="353" height="{card_h}" rx="18" fill="#fff" stroke="#dbe2d2"/>
<rect x="36" y="34" width="36" height="36" rx="10" fill="#eaf2e6"/>
<circle cx="54" cy="52" r="8" fill="none" stroke="#66835b" stroke-width="1.5"/>
<path d="M50 52h8m-4-4v8" stroke="#66835b" stroke-width="1.5" stroke-linecap="round"/>
<text x="83" y="51" fill="#26372d" font-size="15" font-weight="600">{escape(c.title)}</text>
<text x="83" y="69" fill="#768270" font-size="10.5">{escape(c.scope)} · {escape(c.timing)}</text>{arrow}
<text x="37" y="119" fill="{ink}" font-size="{main_size}" font-weight="500">{escape(c.main)}</text>
<text x="37" y="145" fill="#697963" font-size="11">{escape(c.detail)}</text>
{bar}{bottom}</svg>'''
    ET.fromstring(source)
    return source


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for card in CARDS:
        (OUT / f"{card.name}.svg").write_text(draw(card) + "\n", encoding="utf-8")
    print(f"Generated {len(CARDS)} card examples in {OUT}")


if __name__ == "__main__":
    main()

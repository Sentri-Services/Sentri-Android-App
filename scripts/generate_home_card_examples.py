#!/usr/bin/env python3
"""Generate reviewable Home card examples from the target PRD's sample content.

These SVGs are product illustrations. They are not screenshots or live data.
"""

from dataclasses import dataclass
from pathlib import Path
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "home-card-examples"


@dataclass(frozen=True)
class Card:
    slug: str
    title: str
    icon: str
    context: str
    status: str
    value: str
    action: str
    support: str
    progress: str = ""
    done: int = 0
    total: int = 0
    unit: str = ""
    footer: str = "涉及 2 个单元 · 查看任务"
    tone: str = "green"


CARDS = [
    Card("heat-check", "查情", "情", "第 28 批 · 第 2/3 天", "进行中", "8", "头母猪累计发情", "目标发情 20 头", "累计发情 / 目标发情", 8, 20, "头"),
    Card("breeding", "配种", "配", "第 28 批 · 第 2/3 天", "进行中", "8", "头母猪至少配种一次", "已标发情 20 头", "至少配种一次 / 已标发情", 8, 20, "头"),
    Card("return-heat", "返情检查", "返", "第 25 批 · 任务第 3/7 天", "进行中", "2", "头已记录返情", "另有 18 头尚无返情记录"),
    Card("pregnancy", "妊娠检查", "孕", "第 26 批 · 本次检查", "进行中", "5", "头母猪待检查", "其中明确复查 2 头", "本次检查已记录", 8, 20, "头"),
    Card("farrowing", "分娩", "娩", "第 28 批 · 预产窗口", "进行中", "2", "头母猪正在分娩", "另有 4 头今日预产", "已分娩", 7, 18, "头"),
    Card("postpartum", "产后检查", "产", "第 28 批 · 本次评估", "进行中", "3", "头母猪待检查", "其中 1 头有明确逾期", "产后检查已记录", 6, 9, "头"),
    Card("piglet", "仔猪处理", "仔", "第 27 批 · 全日龄计划", "进行中", "", "当前无到期护理", "下次护理：明天 · 任务仍在进行", "全计划护理已完成", 1, 8, "窝"),
    Card("weaning", "断奶检查", "断", "第 26 批 · 本次评估", "进行中", "3", "窝待断奶检查", "明确截止：10 月 13 日", "断奶检查已记录", 5, 8, "窝"),
    Card("treatment", "治疗疗程", "疗", "医嘱 T-108 · 第 2 次／共 3 次", "进行中", "4", "头猪本次待给药", "下一次给药：10 月 13 日 08:00", "本次给药已记录", 6, 10, "头", tone="amber"),
    Card("vaccination", "免疫", "苗", "免疫计划 V-36 · 本次剂次", "进行中", "6", "头猪待接种", "口蹄疫疫苗 · 今天排程", "本次免疫已记录", 10, 16, "头"),
    Card("transfer", "转舍", "转", "转舍单 M-42 · 转出／转入", "进行中", "8", "头猪待确认转舍", "配怀区单元 3 → 分娩区单元 6", "已确认转舍", 12, 20, "头", tone="purple"),
    Card("outbound", "出栏", "栏", "出栏单 O-19 · 育肥猪", "可处理", "9", "头猪待确认出栏", "已指派 · 未设截止时间", "已确认出栏", 11, 20, "头", tone="purple"),
    Card("sampling", "采样", "样", "采样单 S-31 · 猪只目标", "可处理", "3", "个目标待采样", "单元 6 · 未设截止时间", "样本已采", 2, 5, "个目标", footer="单元 6 · 查看采样任务", tone="purple"),
    Card("semen-entry", "采精入口", "精", "公猪舍 · 单元 2", "工具入口", "", "进入采精", "无独立排程任务时不生成待办数", footer="单元 2 · 打开采精工作页", tone="blue"),
]


PALETTE = {
    "green": ("#e8f0e6", "#426b4c", "#f5f8f2"),
    "amber": ("#f6ecda", "#936a2c", "#fcf9f2"),
    "purple": ("#eee9f4", "#765893", "#faf8fc"),
    "blue": ("#e5eff4", "#4b7286", "#f6fafb"),
}


def t(value: str) -> str:
    return escape(value)


def card_markup(card: Card, x: int = 0, y: int = 0) -> str:
    light, dark, wash = PALETTE[card.tone]
    if card.value:
        action_x = 100 if len(card.value) > 1 else 88
        action = (
            f'<text x="48" y="133" class="number" fill="{dark}">{t(card.value)}</text>'
            f'<text x="{action_x}" y="129" class="action">{t(card.action)}</text>'
            f'<text x="48" y="152" class="support">{t(card.support)}</text>'
        )
    else:
        action = (
            f'<text x="48" y="120" class="quiet">{t(card.action)}</text>'
            f'<text x="48" y="143" class="support">{t(card.support)}</text>'
        )
    if card.progress and card.total:
        width = round(368 * min(max(card.done / card.total, 0), 1), 1)
        progress = (
            f'<text x="32" y="187" class="caption">{t(card.progress)}</text>'
            f'<text x="400" y="187" class="caption value" text-anchor="end">{card.done} / {card.total} {t(card.unit)}</text>'
            '<rect x="32" y="198" width="368" height="6" rx="3" fill="#e8eee5"/>'
            f'<rect x="32" y="198" width="{width}" height="6" rx="3" fill="{dark}"/>'
        )
    else:
        if card.slug == "return-heat":
            progress = ""
        else:
            note = (
                "观察结果不代表检查已完成，不展示完成率"
                if card.slug == "heat-check" or card.slug.startswith("heat-check-")
                else "仅显示已确认的任务与时间信息"
            )
            progress = f'<text x="32" y="188" class="caption">{note}</text>'
    return f'''<g transform="translate({x} {y})">
  <rect x="8" y="8" width="416" height="236" rx="18" fill="#ffffff" stroke="#d9e3d7" stroke-width="1.4"/>
  <rect x="26" y="27" width="34" height="34" rx="10" fill="{light}"/>
  <text x="43" y="50" text-anchor="middle" font-size="15" font-weight="700" fill="{dark}">{t(card.icon)}</text>
  <text x="73" y="43" class="title">{t(card.title)}</text>
  <text x="73" y="59" class="sub">{t(card.context)}</text>
  <rect x="334" y="28" width="73" height="24" rx="12" fill="{light}"/>
  <text x="370.5" y="45" text-anchor="middle" font-size="11" fill="{dark}">{t(card.status)}</text>
  <rect x="27" y="79" width="378" height="84" rx="13" fill="{wash}"/>
  {action}
  {progress}
  <line x1="26" y1="216" x2="406" y2="216" stroke="#e4eae0"/>
  <text x="32" y="232" class="footer">{t(card.footer)}</text>
  <path d="M388 226l5 5-5 5" fill="none" stroke="{dark}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
</g>'''


STYLE = """<style>
  text{font-family:'PingFang SC','Noto Sans CJK SC','Microsoft YaHei',sans-serif}
  .title{fill:#26372d;font-size:16px;font-weight:700}
  .sub{fill:#748274;font-size:10px}
  .number{font-size:37px;font-weight:700}
  .action{fill:#26372d;font-size:14px;font-weight:600}
  .quiet{fill:#26372d;font-size:15px;font-weight:600}
  .support{fill:#6c7c6d;font-size:11px}
  .caption{fill:#697c6d;font-size:10px}
  .caption.value{fill:#354b3b;font-weight:600}
  .footer{fill:#506e54;font-size:11px}
</style>"""


def svg(width: int, height: int, title: str, inner: str) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" role="img" aria-label="{t(title)}">'
        f'<title>{t(title)}</title>{STYLE}'
        f'<rect width="{width}" height="{height}" fill="#f0f4ee"/>{inner}</svg>\n'
    )


def write_svg(path: Path, content: str) -> None:
    ET.fromstring(content)
    path.write_text(content, encoding="utf-8")


def sheet(name: str, heading: str, group: list[Card]) -> None:
    rows = (len(group) + 1) // 2
    height = 87 + rows * 262
    inner = (
        f'<text x="28" y="42" font-size="22" font-weight="700" fill="#26372d">{t(heading)}</text>'
        '<text x="28" y="62" font-size="11" fill="#748274">示例数字仅用于核对字段和层次；非真实任务或已实现截图</text>'
    )
    for i, card in enumerate(group):
        inner += card_markup(card, 16 + (i % 2) * 442, 80 + (i // 2) * 262)
    write_svg(OUTPUT / f"{name}.svg", svg(900, height, heading, inner))


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for card in CARDS:
        write_svg(OUTPUT / f"{card.slug}.svg", svg(432, 252, f"{card.title}示例卡片", card_markup(card)))
    sheet("production-core", "生产任务：八类主卡", CARDS[:8])
    sheet("operations", "业务任务与采精入口", CARDS[8:])
    print(f"Generated {len(CARDS)} cards and 2 sheets in {OUTPUT}")


if __name__ == "__main__":
    main()

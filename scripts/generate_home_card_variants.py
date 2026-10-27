#!/usr/bin/env python3
"""Render Home card content variants for product review."""

from dataclasses import dataclass, replace
from pathlib import Path
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

from generate_home_card_examples import CARDS, Card, card_markup, svg


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "home-card-variants"
BASE = {card.slug: card for card in CARDS}


@dataclass(frozen=True)
class Variant:
    kind: str
    branch: str
    label: str
    when: str
    decision: str
    card: Card


VARIANTS: list[Variant] = []


def add(kind: str, branch: str, label: str, when: str, decision: str, **changes: object) -> None:
    source = BASE[kind]
    card = replace(source, slug=f"{kind}-{branch}", **changes)
    VARIANTS.append(Variant(kind, branch, label, when, decision, card))


# Lifecycle placement (Upcoming / Today's work / Closed) is evaluated before
# selecting one business headline. Transfer direction is a scope projection.

# Pregnancy: unknown outcome may produce an explicitly scheduled recheck.
add("pregnancy", "due", "常规与复查同时到期", "本次窗口已开，常规和复查均有对象，且没有明确逾期对象。", "复查包含在主数内，不重复计猪。")
add("pregnancy", "recheck", "仅明确复查到期", "常规检查本轮无待办，仅有已排程复查。", "标题和进度限定为本次复查。", context="第 26 批 · 明确复查", value="2", action="头母猪待复查", support="复查窗口：今天 14:00", progress="本次复查已记录", done=1, total=3)
add("pregnancy", "overdue", "检查明确逾期", "本次检查配置明确截止，截止已过。", "主数包括逾期对象，辅助只报子集。", value="6", action="头母猪本次待检查", support="其中 2 头超过明确截止")
add("pregnancy", "next", "检查窗口未到", "权威状态仍进行中，本次无人到期，已知下一日期。", "进度保留，本卡不移动到未来预告。", value="", action="当前无到期妊娠检查", support="下次检查：10 月 15 日")
add("pregnancy", "unknown", "复查未排程", "出现不确定结果，但无权威复查时间。", "不自动生成今天待复查。", value="", action="复查安排待确认", support="任务进行中 · 已有不确定结果 2 头")

# Farrowing: live record outranks date estimates; expected date is not a deadline.
add("farrowing", "live", "真实分娩进行中", "已有母猪的分娩开始记录。", "真实进行记录优先于预产日预测。")
add("farrowing", "past-expected", "预产日已过", "没有真实进行记录；部分母猪预产日已过且尚未分娩。", "提示核对母猪与记录，不标成任务逾期。", value="3", action="头母猪预产日已过", support="尚无分娩记录")
add("farrowing", "due-today", "今日预产", "没有真实进行记录或过预产日对象。", "只说预产，不断言已经开始分娩。", value="4", action="头母猪今日预产", support="暂无正在分娩记录")
add("farrowing", "next", "下一个预产日", "当前没有真实进行或今日预产对象。", "显示最近预产日，任务仍进行中。", value="", action="当前无今日预产母猪", support="下一预产日：10 月 15 日")
add("farrowing", "missing-date", "预产时间缺失", "任务在进行，但缺可靠交配／预产日。", "不填默认 114 天来制造到期数。", value="", action="预产时间待核对", support="任务进行中 · 查看母猪名单")

# Postpartum: each sow's actual farrowing time anchors its window.
add("postpartum", "due", "产后评估已到期", "实际分娩时间存在，本次检查窗口已打开，且没有明确逾期或复查对象。", "显示当前待检查数和窗口。", support="产后评估窗口已开")
add("postpartum", "overdue", "明确检查截止已过", "本次检查有明确截止，截止已过且仍有未记录对象。", "待检查数包含逾期对象，辅助只报逾期子集。", support="其中 1 头超过明确截止")
add("postpartum", "recheck", "明确复查到期", "复查是独立排程且已到窗口，没有明确逾期对象。", "异常答案本身不自动产生复查。", value="2", action="头母猪待产后复查", support="明确复查 · 今天 15:00", done=3, total=5)
add("postpartum", "next", "等待下一评估", "已分娩对象的检查窗口尚未到。", "保留进度，展示最近计划时间。", value="", action="当前无到期产后检查", support="下次评估：明天 09:00")
add("postpartum", "farrowing", "等待真实分娩", "本任务进行中，但当前没有可用实际分娩时间。", "不提前把未分娩对象放入分母。", value="", action="等待分娩记录", support="有实际分娩时间后计算检查窗口", progress="", done=0, total=0)

# Piglet processing: count unique litters across all required care items.
add("piglet", "due", "多种护理同日到期", "至少一项必做护理已到期且未记录，没有明确逾期项目。", "同窝多个护理项仍只计一窝。", value="4", action="窝有到期护理", support="跨 2 种护理项目 · 按窝去重", done=1, total=8)
add("piglet", "overdue", "必做护理明确逾期", "已配置项目截止，仍有未做的必做项。", "过期窝包含于主数。", value="5", action="窝有到期护理", support="其中 2 窝超过项目截止")
add("piglet", "next", "等待下一日龄护理", "今天没有到期护理，后续计划已知。", "任务继续留在主列表。", value="", action="当前无到期护理", support="下次护理：明天 · 任务仍在进行")
add("piglet", "litters", "等待新生窝", "没有可纳入计划的分娩／窝记录。", "不把未来护理虚构成今天待办。", value="", action="等待新生窝记录", support="暂无下一次护理日期", progress="", done=0, total=0)
add("piglet", "plan-unknown", "必做项配置不明", "护理项目或可选项到期规则缺失。", "不自动跳过、完成或推导百分比。", value="", action="护理计划待核对", support="任务进行中 · 到期数暂不可用", progress="", done=0, total=0)

# Legacy LACTATION is the old weaning check; the new design changes its object granularity.
add("weaning", "due", "当前可检查", "有明确检查窗口，窝／栏已进入窗口，且没有明确逾期对象。", "检查和实体转舍分开。")
add("weaning", "overdue", "明确检查截止已过", "检查计划有截止且有未记录对象。", "主数含逾期对象，辅助提示子集。", value="4", action="窝待断奶检查", support="其中 1 窝超过明确截止")
add("weaning", "next", "等待检查窗口", "本任务进行中，但本单元／范围尚未到检查日。", "显示下一检查日。", value="", action="当前无到期断奶检查", support="下次检查：10 月 15 日")
add("weaning", "unknown", "窗口缺失", "有任务但缺稳定对象名单或检查计划。", "显示状态，不补待检窝数和比例。", value="", action="检查安排待核对", support="任务进行中 · 详情待接入", progress="", done=0, total=0)

# Treatment: a course can contain multiple sessions; never use a session bar as lifecycle.
add("treatment", "due", "本次给药到期", "当前轮次计划时间已到，有未记录对象，且没有明确逾期对象。", "显示轮次和去重猪只。")
add("treatment", "overdue", "有明确给药截止", "医嘱明示本次截止且有未给药记录。", "只报告事实，不推荐补剂量。", value="5", action="头猪本次待给药", support="其中 2 头超过医嘱截止")
add("treatment", "next", "等待下次给药", "当前轮次已记录或尚未到下一次时间。", "疗程仍进行中，不因本轮满条关闭。", value="", action="当前无到期给药", support="第 3 次给药：明天 08:00")
add("treatment", "unknown-plan", "给药计划不明", "医嘱缺本次排程或目标名单。", "隐藏待给药数与比例，打开医嘱核对。", value="", action="本次给药安排待核对", support="疗程进行中 · 查看医嘱", progress="", done=0, total=0)
add("treatment", "legacy-status", "旧医嘱状态不支持", "旧数据返回未纳入本版产品的中断状态。", "只读提示，不把它当暂停／继续按钮。", status="状态待确认", value="", action="疗程状态待确认", support="当前不可直接执行给药", progress="", done=0, total=0)

# Vaccination: schedule and explicit deadline differ from created_at.
add("vaccination", "due", "本次排程到期", "已指派本次免疫，排程时间已到，且没有明确逾期对象。", "主数为本剂次未接种的去重猪只。")
add("vaccination", "overdue", "明确免疫截止已过", "计划有明确截止，仍有本剂次未接种对象。", "不用“下发 N 天”判断逾期。", value="7", action="头猪待接种", support="其中 2 头超过计划截止")
add("vaccination", "next", "等待本次排程", "免疫任务进行中，当前范围暂无到期猪只。", "显示下一剂次／时间，不生成执行按钮。", value="", action="当前无到期接种", support="下一排程：10 月 15 日")
add("vaccination", "unknown-target", "目标名单不完整", "疫苗可识别，但目标数或排程未验证。", "不显示伪造分母、百分比。", value="", action="免疫目标待核对", support="口蹄疫疫苗 · 任务进行中", progress="", done=0, total=0)

# Transfer: one order, direction is a scope property rather than another task.
add("transfer", "confirmed-issue", "接收位置有已确认问题", "有可处理的接收位置异常，已与本转舍单绑定。", "先显示问题，再在详情看移动名单；不得臆断栏位容量。", value="1", action="处接收位置需处理", support="已确认 · 影响转舍单 M-42")
add("transfer", "due", "当前可确认移动", "转舍计划窗口已开、有未确认移动，且没有已确认的接收位置问题。", "总览同单只一张。")
add("transfer", "next", "等待接收窗口", "本单进行中，但当前没有可确认移动对象。", "显示下一个明确窗口。", value="", action="当前无待确认转舍", support="下一接收窗口：明天 09:00")
add("transfer", "destination", "接收位置未定", "已有转舍单与对象，但目标单元尚未指定。", "提示补位置，不推断可执行移动。", value="", action="接收位置待确定", support="转舍单 M-42 · 查看任务")
add("transfer", "unit-out", "单元范围：转出", "当前单元是来源单元。", "只缩小范围与方向，任务 ID 不变。", context="转舍单 M-42 · 单元 3 转出", value="5", action="头猪待确认转出", support="转往分娩区单元 6", done=7, total=12, footer="当前单元 · 查看转出工作")
add("transfer", "unit-in", "单元范围：转入", "当前单元是接收单元。", "与转出同属一单，按方向显示接收数。", context="转舍单 M-42 · 单元 6 转入", value="3", action="头猪待确认转入", support="来自配怀区单元 3", done=9, total=12, footer="当前单元 · 查看转入工作")

# Outbound: old data may have no planned execution time.
add("outbound", "undated", "已指派无截止", "出栏单已指派、当前可执行，但没有计划时间。", "明确写未设截止，不根据创建时间判逾期。")
add("outbound", "scheduled", "明确今日执行", "出栏单有可靠执行窗口且已到。", "当前待确认数优先于累计比例。", status="进行中", value="6", action="头猪待确认出栏", support="计划执行：今天 14:00")
add("outbound", "unknown-target", "目标口径未确认", "单据存在，但选定对象或分母无法可靠读取。", "只显示任务身份和状态。", status="进行中", value="", action="出栏对象待核对", support="出栏单 O-19 · 查看详情", progress="", done=0, total=0)

# Sampling: target type and location determine scope; each task remains distinct.
add("sampling", "pig", "猪只目标", "本采样单有明确猪只目标及单元。", "按目标 ID 去重；一单一张卡。")
add("sampling", "environment", "环境目标", "本采样单目标是有可靠分区／单元归属的环境点。", "目标单位是采样点，不写成猪只。", context="采样单 S-32 · 环境目标", value="2", action="个点位待采样", support="分娩区单元 6 · 未设截止", progress="样本已采", done=1, total=3, unit="个点位", footer="分娩区单元 6 · 查看采样任务")
add("sampling", "farm-wide", "人员／物料全场目标", "目标无明确分区归属，但属于已安排任务。", "只在全场任务入口出现一次。", context="采样单 S-33 · 人员目标", value="4", action="个目标待采样", support="全场范围 · 未设截止", progress="样本已采", done=0, total=4, unit="个目标", footer="全场任务 · 查看采样任务")
add("sampling", "unknown-scope", "位置归属不足", "目标地点字符串不足以验证分区。", "不复制到每个分区；从全场入口核对。", context="采样单 S-34 · 地点待核对", value="", action="目标归属待核对", support="不能按文本前缀猜测分区", progress="", done=0, total=0, footer="全场任务 · 查看采样任务")


TYPE_NOTES = {
    "pregnancy": "旧版把未孕与未记录混入待检，并把不确定结果作单独复查线索；新版以本次提交和明确复查计划判定。",
    "farrowing": "旧版同时计算预产、超预产、正在分娩；新版真实分娩记录优先，预产日已过只是核对提示，不等同任务逾期。",
    "postpartum": "旧版单元过滤与时间差计算需要复核；新版仅由实际分娩时间和任务配置窗口计算。",
    "piglet": "旧版按护理项目逐项统计，并有缺日期时从当前时间推计划的回退；新版按窝去重，缺计划不补今天。",
    "weaning": "旧 LACTATION 页面标题就是断奶检查；新版改按窝／栏，需先建立新旧记录对象映射。",
    "treatment": "旧卡强调药品、剂量、频次及整疗程比例；新版首页只聚焦本次给药窗口，剂量留医嘱详情。",
    "vaccination": "旧卡用“下发 N 天”；新版使用排程与明确截止，创建时间不能代替执行时间。",
    "transfer": "旧首页按单元拆成转出／转入卡；新版一单一张，单元仅改变方向和计数。",
    "outbound": "旧卡以出栏比例和范围为主；新版优先给出本单当前待确认数，缺时间时明确标未设截止。",
    "sampling": "旧首页合并最近七天创建的采样任务；新版一任务实例一卡，按真实目标类型和地点决定范围。",
}


def write_svg(path: Path, source: str) -> None:
    ET.fromstring(source)
    path.write_text(source, encoding="utf-8")


def sheet(kind: str, items: list[Variant]) -> None:
    rows = (len(items) + 1) // 2
    height = 85 + rows * 282
    heading = f"{BASE[kind].title} · 展示分支"
    inner = (
        f'<text x="28" y="42" font-size="22" font-weight="700" fill="#26372d">{escape(heading)}</text>'
        '<text x="28" y="62" font-size="11" fill="#748274">同一任务类型按事实择一显示；示例数据不是业务默认值</text>'
    )
    for i, item in enumerate(items):
        x = 16 + (i % 2) * 442
        y = 102 + (i // 2) * 282
        inner += f'<text x="{x + 24}" y="{y - 10}" font-size="12" font-weight="700" fill="#426b4c">{escape(item.label)}</text>'
        inner += card_markup(item.card, x, y)
    write_svg(OUTPUT / f"{kind}.svg", svg(900, height, heading, inner))


def generate_doc(grouped: dict[str, list[Variant]]) -> None:
    parts = [
        "# 首页任务卡多场景展示方案",
        "",
        "> 2026-10-14 · 早期多场景探索，部分图已被后续产品决定替代。当前卡片统一看[生产任务首页卡片总览](home-production-task-cards.md)。示例数字不是农场数据，也不是 Android 已实现页面。查情、配种、返情分别见[查情](home-heat-card-revision.md)、[配种](home-breeding-card-revision.md)、[返情暂选卡](home-return-heat-card-revision.md)。",
        "",
        "**示例时钟：** 图片统一假设今天是 10 月 12 日；“今天”“明天”和图中的固定日期都按这一虚构时点理解，不表示当前真实任务日期。",
        "",
        "推导依据：[旧版首页任务计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useTaskData.ts)、[返情与孕检计算](../../Sentri/mobile_webapp/src/pages/home/components/hooks/useReproductiveTaskData.ts)、[旧版任务卡分发](../../Sentri/mobile_webapp/src/pages/home/components/TaskRenderer.tsx)及 [Sentri-UI 任务卡规则](../../Sentri-UI/ux/research/home/HOME-TASK-DISPLAY-RULES.md)。旧代码中的计算是证据，不直接作为新版医疗、截止或状态规则。",
        "",
        "## 先决定卡片在哪里，再决定显示什么",
        "",
        "1. **权威任务状态先归栏。** 已完成／已提前结束进已关闭收据；尚未开始且未来七天进入执行期进紧凑预告；已开始、今日／此前计划开始但未启动、已指派且可执行的无时间任务进主列表。记录完成率绝不产生另一种任务状态。",
        "2. **主列表卡再选一条主信息。** 可信且有处理去向的现场异常 → 明确截止已过的对象 → 当前有窗口的对象 → 下一个已知动作 → 等待上游事实 → 数据不足的安全说明。分娩的真实进行记录优先于日期预测。相同对象不能在主数和辅助数相加。",
        "3. **辅信息只放一条。** 优先写明确逾期子集，其次写影响本次执行的轮次／复查／路线，最后写下一次时间。更多详情留在任务页。进度条仅在分母可信时显示，并写清统计内容。",
        "4. **范围与离线只改投影。** 总览和单元沿用同一任务 ID；单元只缩小对象与方向。离线待上传提示独立于服务端进度，不能使卡片变成完成。未知、加载失败、无权限与真实 0 分开处理。",
        "",
        "![通用状态、范围及数据叠加](home-card-variants/shared.svg)",
        "",
        "这张通用图展示未开始预告、完成／提前结束收据、单元范围、本机待上传、记录 100% 但任务仍进行中、单元本轮已做完、计划开始日已过却未启动、状态未知，以及关键数据缺失。查情、配种、返情检查分别采用单独分支；转舍的转出／转入另外示范单元范围投影。",
        "",
        "## 逐类分支",
        "",
        "### 查情（早期索引）",
        "",
        "![查情首页总览卡](home-heat-card-revision.svg)",
        "",
        "沿用旧版总览的累计发情／目标发情与任务天数；目标达到、目标缺失、单元有迹象见[查情分支图和字段说明](home-heat-card-revision.md)。旧仓库中保留的轮次文案不进入首页卡。",
        "",
        "### 配种（总览与单元分支）",
        "",
        "![配种首页总览卡](home-breeding-card-branches/overview-recorded.svg)",
        "",
        "总览沿用旧版已配种／已发情母猪；单元按已有配种次数和间隔区分可配、等待与已达次数。图片及旧版计算差异见[配种卡片修订](home-breeding-card-revision.md)。",
        "",
        "### 返情检查（总览与单元分支）",
        "",
        "![返情检查暂选总览卡](home-return-heat-card-branches/overview-no-stats.svg)",
        "",
        "本轮暂沿旧版结果与预计日期口径；后端日统计快照未修复时不画饼点。具体分支见[返情暂选卡](home-return-heat-card-revision.md)。[新版候选](home-return-heat-feature-alignment.md)待 feature 定稿后再决定。",
        "",
    ]
    for kind, items in grouped.items():
        title = BASE[kind].title
        parts.extend([
            f"### {title}（{len(items)} 种场景）",
            "",
            TYPE_NOTES[kind],
            "",
            f"![{title}的全部主信息分支](home-card-variants/{kind}.svg)",
            "",
            "| 分支与独立图片 | 触发条件 | 卡片取舍 |",
            "| --- | --- | --- |",
        ])
        for item in items:
            path = f"home-card-variants/{kind}/{item.branch}.svg"
            parts.append(f"| [{item.label}]({path}) | {item.when} | {item.decision} |")
        parts.append("")
    parts.extend([
        "## 不出卡与边界",
        "",
        "- 草稿、未指派且不可执行的无时间工作、无任务实例的普通工具入口，不生成首页任务卡。旧版采精按公猪存栏生成入口，当前只在适用单元工具箱显示[采精入口示例](home-card-examples/semen-entry.svg)。",
        "- 权威状态无法识别时只读显示“状态待确认”；涉及对象名单缺失、时间无效或分页不完整时，不展示推算的待办数、百分比或“逾期”。",
        "- 显示真实 0 时需有完整名单和有效窗口证据；否则使用“待核对”。单元本轮已记录只说明该单元，不改变全任务状态。",
        "- 治疗疗程、免疫计划和转舍单的完整生命周期在 Sentri-UI 仍是提案；有赖后端确认的分支以条件表述，不把示例认作已上线能力。",
        "",
        "图片由[生成脚本](../scripts/generate_home_card_variants.py)和[基础卡片生成器](../scripts/generate_home_card_examples.py)生成。修改分支后先运行两个脚本，再逐张检查图片与本表是否一致。",
        "",
    ])
    (ROOT / "docs" / "home-card-variants.md").write_text("\n".join(parts), encoding="utf-8")


def shared() -> None:
    # Compact lifecycle states use their own template; they are not full task cards.
    compact = [
        ("妊娠检查 · 第 26 批", "10 月 16 日开始", "未开始 · 未来七天预告"),
        ("分娩 · 第 27 批", "今天 09:10 完成", "已关闭 · 只读收据"),
        ("仔猪处理 · 第 25 批", "今天 10:20 提前结束", "已关闭 · 只读收据"),
    ]
    pieces = [
        '<text x="28" y="42" font-size="22" font-weight="700" fill="#26372d">通用状态与叠加示例</text>',
        '<text x="28" y="62" font-size="11" fill="#748274">权威状态归栏；范围和本机记录只改变内容，不改变状态</text>',
    ]
    for i, (title, main, sub) in enumerate(compact):
        x = 20 + (i % 2) * 442
        y = 78 + (i // 2) * 120
        pieces.append(
            f'<g transform="translate({x} {y})"><rect x="8" y="8" width="416" height="104" rx="17" fill="#fff" stroke="#d9e3d7"/>'
            f'<text x="30" y="38" font-size="15" font-weight="700" fill="#26372d">{escape(title)}</text>'
            f'<text x="30" y="64" font-size="14" fill="#426b4c">{escape(main)}</text>'
            f'<text x="30" y="88" font-size="11" fill="#748274">{escape(sub)}</text>'
            '<path d="M385 51l5 5-5 5" fill="none" stroke="#426b4c" stroke-width="1.5"/></g>'
        )
    examples = [
        replace(BASE["farrowing"], slug="unit", context="第 28 批 · 当前单元 6", value="1", support="另有 2 头今日预产", done=4, total=8, footer="当前单元 · 查看分娩工作"),
        replace(BASE["pregnancy"], slug="offline", support="含本机未上传 1 条 · 服务端数字未变", footer="涉及 2 个单元 · 查看任务"),
        replace(BASE["breeding"], slug="full-bar", value="20", action="头母猪至少配种一次", support="已标发情 20 头 · 任务仍进行中", progress="至少配种一次 / 已标发情", done=20, total=20),
        replace(BASE["vaccination"], slug="missing", value="", action="免疫目标待核对", support="进行中 · 不显示未知分母", progress="", done=0, total=0),
        replace(BASE["weaning"], slug="unit-finished", context="第 26 批 · 当前单元 6", value="", action="本单元本轮已记录", support="整项任务仍进行中 · 其他单元有工作", done=4, total=4, footer="当前单元 · 查看断奶检查"),
        replace(BASE["outbound"], slug="past-start", status="未开始", value="", action="计划已开始 · 尚未启动", support="计划开始：昨天 · 没有明确截止", done=0, total=20, footer="涉及 2 个单元 · 查看出栏单"),
        replace(BASE["treatment"], slug="unknown-status", status="状态待确认", value="", action="任务状态待确认", support="只读显示 · 不生成待办数", progress="", done=0, total=0),
    ]
    for i, item in enumerate(examples):
        pieces.append(card_markup(item, 16 + (i % 2) * 442, 330 + (i // 2) * 262))
    write_svg(OUTPUT / "shared.svg", svg(900, 1402, "通用状态与叠加示例", "".join(pieces)))


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    grouped: dict[str, list[Variant]] = {}
    for item in VARIANTS:
        grouped.setdefault(item.kind, []).append(item)
        path = OUTPUT / item.kind
        path.mkdir(exist_ok=True)
        write_svg(path / f"{item.branch}.svg", svg(432, 252, f"{BASE[item.kind].title}：{item.label}", card_markup(item.card)))
    for kind, items in grouped.items():
        sheet(kind, items)
    shared()
    generate_doc(grouped)
    print(f"Generated {len(VARIANTS)} variants across {len(grouped)} task types, 1 shared sheet and 1 guide")


if __name__ == "__main__":
    main()

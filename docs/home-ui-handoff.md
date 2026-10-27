# Home UI 原生实现与设计反馈

## 最新 Workbench UI（2026-10-20，待用户审核）

本轮以 Sentri-UI `main` 提交 `6df878f096c371781d0ad4c1dfbba6739b9a3bcf` 的 `features/workbench/PRD.md`、`feature.json`、`overview-redesign.md` 和 `ux/system/home-astra-prototype.js/css` 为准。以下内容取代本文后半部分 `410b1dc` 阶段的旧首页实现描述；旧段落保留为历史记录。设计状态仍是 `in-design`，Unit 的 U2 改版明确处于待确认状态，因此原生 Unit 使用当前 feature 的完整任务卡结构。

- **总览**：生产任务按阶段和批次排成两列四槽磁贴，涵盖立即待做、部分逾期、全部等待、超出计划、全部记录完待结束及状态待核对；每个 open 实例都展示。健康区先列待处理/观察中名单，再列计划类任务磁贴。其他工作保持一张扁平列表。已结束任务只在生产区下方显示安静的一行，点开再看清单。
- **单元**：保留巡查、注意标记、环境设备入口与完整任务卡；数量缩小到当前单元，进行中、等待、无待做、状态待核对和已结束均可看。单元注意标记有正常、清空、仅健康、仅饲喂、仅维修、多项偏高、读取不明七组样例。
- **日程**：独立全屏，无底栏；上周/本周/下周、全部/生产/健康/其他、类型/批次行、七天不同单元格状态、首次自动打开说明、整行展开一周数字。样例仅有三周，不推断真实日期或后端计划。
- **交互分支**：分区切换、选择单元（含当前与无任务）、待处理/观察中跨单元名单、今日已结束清单与结果预览、任务详情占位与本地提前结束演示、工具箱和记录占位均可进入。Assistant、扫码、真实巡检、环境控制、任务详情与数据同步由各自 feature 承担，本轮入口明确提示未连接。
- **数据模板**：无手动状态选择器。在首页或单元页下拉刷新，依次循环普通、繁忙、全部待做、全部等待、单项注意标记、未知、空、加载、无权限、读取失败等 11 套 mock 模板。本机记录徽标另外按每次刷新循环“待上传 3 → 上传中 3 → 需处理 1”。刷新只改变内存 UI，不发起网络请求。农场、日期、任务与数量均为样例。
- **底栏与本机记录入口**：扫码按钮按首页原型做成 68dp 方块，从底栏向上凸出 23dp，保留 5dp 浅色外沿。右上角按 Data sync 的三张状态样图做本机记录徽标：待上传为暖黄色“↑ 3”，上传中为浅蓝色旋转“↻ 3”，需处理为浅红色“异常 1 / Fix 1”。点击只打开“保存的工作”占位说明，不会启动上传。当前状态与数量均为 mock，未连接真实队列、认证或同步结果。

Data sync 设计源存在待统一处：`feature.json`、`ux/system/home-astra-prototype.js`、`data-sync-shell.css` 和用户提供的状态截图保留了全机数量及三种状态；`features/data-sync/PRD.md` 的页眉徽标段落则写首页不显示数字、只分等待与需处理两种形态。本轮按用户明确指出的三张样图实现 Android 视觉，不据此更改同步业务规则或 Sentri-UI。

### Android 组件适配与设计反馈

已复用 `SentriPanel`、`SentriRow`、`SentriSheet`、`SentriProgress`、按钮、字体、色板与图标。`SentriRow` 原来的 `current` 会加绿底与左线，和新版单元选择菜单的“白底、加粗、墨色对勾”不合；本轮在 Android 增加 `menuCurrent` 变体。跨单元健康名单需要朴素病症标签，给同一组件增加 `trailingTag`。这两项只在 Android 生效，待用户审核再决定是否回写设计库。

Web 的四槽 `TaskTile` 和七天 `WeekBoard` 没有对应的原生组件：本轮新增 Android 本地的 `SentriWorkTile`，周板暂由 Workbench 页面组合实现；单元格的已记录、今天、计划、预计虚线、等待、超出计划与跨周标记已覆盖。建议用户审核后把原生组件约束和菜单选中变体作为 Sentri-UI 组件适配反馈。本轮没有修改 Sentri-UI 文件。

### 本轮验证

- `:app:assembleDevDebug`、`:app:testDevDebugUnitTest`、`:app:lintDevDebug` 通过；API 36 模拟器 `HomeScreenTest` 的新版页面与下拉刷新路径通过。
- `scripts/verify.sh` 在现有工作树的 GraphQL 契约清单变更检查处停止；本轮没有修改 GraphQL。不能记作整体验证通过。
- 模拟器截图：[总览](../verification/home-ui/latest-workbench/overview.png)、[单元](../verification/home-ui/latest-workbench/unit.png)、[单元选择](../verification/home-ui/latest-workbench/unit-picker.png)、[生产日程](../verification/home-ui/latest-workbench/schedule.png)、[全部日程](../verification/home-ui/latest-workbench/schedule-all.png)、[待上传](../verification/home-ui/latest-workbench/footer-saved-waiting.png)、[上传中](../verification/home-ui/latest-workbench/footer-saved-uploading.png)、[需处理](../verification/home-ui/latest-workbench/footer-saved-attention.png)；[上传中动画录屏](../verification/home-ui/latest-workbench/saved-work-uploading-demo.mp4)。截图为英文系统样例；对应中文文案已放入资源。


## 来源和范围

- 设计仓库：`../Sentri-UI`，基准提交 `410b1dcca739b2609544ef2783dd9a711f941226`；前次经确认的四项反哺保存在该仓库未提交的工作树中。本轮视觉校准尚未回写。
- 用户口中的 `feature/home` 对应设计目录 `features/workbench/`；入口为 `workbench.today`。`feature.json` 将首页及其分区、单元、保存工作等屏幕标为 `in-design`，设置页为 `placeholder`。
- 依据：`features/workbench/PRD.md`、`feature.json`、`ux/system/home-astra-prototype.html`、`ux/system/astra-home-task-card.js/css`、`ux/design-system/tokens.json` 和 `ux/design-system/components/bundle.js`。
- 本轮只实现静态 UI，所有农场、任务和离线记录都是原型样例，不接任务、同步、相机、认证或网络数据。登录并选定厂区后默认展示此首页；未登录时仍可从接入验证页的“首页 UI 预览”进入。首页返回打开接入验证页，以便使用现有测试入口。

## Android 落地

| 设计内容 | 原生位置 | 说明 |
| --- | --- | --- |
| Astra 浅色 palette、字体、基础间距/圆角 | `app/src/main/java/com/sentri/nativeapp/design/SentriTheme.kt`、`SentriComponents.kt` | 首页单独使用 `SentriAppTheme`；接入验证页继续使用原来的 Material 主题。 |
| Plus Jakarta Sans、IBM Plex Mono | `app/src/main/res/font/` | 字体随包离线使用；许可在 `app/src/main/assets/font_licenses/`。 |
| 53 个规范图标与 3 个别名 | `app/src/main/res/drawable/sentri_ic_*.xml`、`SentriIcons.kt` | `scripts/sync_sentri_icons.py` 从相邻设计源生成。新增 `production` 和 `care`；未知名称不回退到错误图标。 |
| 可复用组件 | `SentriComponents.kt` | Icon、Panel、SectionHeading、Button、IconButton、Banner、Row、Sheet、Progress；按本轮首页实际使用的范围移植。 |
| `workbench.today`、`sections`、`choose-unit`、`saved-work` | `app/src/main/java/com/sentri/nativeapp/home/` | 首页卡片、分区与单元切换、保存记录弹层。单元选择只影响演示范围；计数按原型样例缩小。 |
| `workbench.unit` 的入口 | `HomeScreen.kt` | 单元巡查入口与样例单元基本情况；巡查详情属于其他功能。 |
| `task-preview`、`closed-task` 入口 | `HomeScreen.kt` | 仅展示原型中的未设计提示；不做任务变更。 |

`workbench` 的生产可用性尚未获得设计确认。Card 的到期、异常、完成排序、真实任务状态和保存队列均不能从这些演示常量推断；接真实数据时需独立对照 PRD 和后端契约。

## 本轮首页视觉校准（Android，待确认毛玻璃效果）

- 单元选择 `SentriSheet` 打开时，Android 12 及以上使用系统窗口背景模糊（4dp 半径）和半透明暗色遮罩；系统不支持窗口模糊或低版本时保留遮罩。原先对整个 Compose 首页逐帧执行 0→9dp 模糊，在模拟器中明显卡顿，已移除。Sheet 为展开态，标题下及按钮区上方有分隔线，底部固定带左箭头的 Back 按钮，并留出系统手势安全空间。当前先在 Android 预览确认效果，尚未回写 Sentri-UI。
- 顶部分区入口改为横向短距离滑动加淡入淡出，返回方向相反；原型页结构保持不变。
- 总览/单元切换按 `home-astra-prototype.css` 使用一个浅绿色切换槽，白色选中块，并在单元入口同排显示单元数，下拉箭头贴右对齐。
- 单元列表按 `showUnits()` 及共享 Row 规则显示总单元数、每个单元的猪/栏位与任务数。当前单元使用浅绿底色与左侧标记；按住可见更深的按压底色。
- 底栏扫码按钮上移，圆形外沿用底栏底色形成凸出的留白，顶部分隔线在按钮处断开。

本轮依据 `features/workbench/PRD.md`、`ux/system/home-astra-prototype.js/css`、`ux/design-system/components/Sheet/README.md` 与 `bundle.css`。视觉调整只作用于静态预览，不赋予扫码、任务或单元操作新的业务能力。毛玻璃是否反哺 Sentri-UI，等 Android 实机/模拟器效果由用户确认后决定。

## 已确认并回写到 Sentri-UI

用户确认以下四项后，已在 Sentri-UI 的本地工作树回写；保持 `workbench` 的 `in-design` 状态。

1. **图标来源统一。** `SentriIcons` 增加 `production` 和 `care`，Home 任务卡删去本地 SVG 路径并使用共享注册表；Android 重新生成图标资源，任务卡与 Web 同用 `production`。
2. **静态样例跨原型衔接。** PRD、screen 元数据和原型说明都标明 Home 的 Piglet Batch 27（8 pens / 2 units）与链接到的 Batch 40（12 pens）是独立设计样例；跳转只演示目的地。样例本身尚未统一，接真实数据前仍需处理。
3. **范围外入口行为。** PRD 与 screen 元数据说明 Assistant、Scan、Toolbox 是其他样例页的原型导航。仅有 Home UI 的原生预览打开“不接入”说明，不声称执行了真实操作；生产导航待各自 feature 落地。
4. **首页样例日期。** 原型和 Android 英文预览都显示 `Sample · Thu, 8 Jul`；PRD 与 screen 元数据将真实日期来源留为未决。

以上调整不改变任务完成、离线同步或业务语义；涉及业务规则的任何新决定仍需产品确认。

## 验证

- `SENTRI_GRADLE_DIRECT=1 ./scripts/verify.sh`：构建、JVM、Lint 与契约检查通过。
- `HomeScreenTest`：API 36 模拟器设备测试 3 项通过，检查总览/单元样例计数切换、保存记录预览，以及单元 Sheet 的数量、Back 关闭和重新打开后的当前单元文案。
- Sentri-UI `node --check`（3 个改动脚本）、`node scripts/build-atlas.mjs --check` 和 `git diff --check` 通过；Chrome 打开 Home 原型，确认日期与共享 `production` 图标渲染。53 个 Android Vector 的路径与设计源逐项相同。
- API 36 模拟器安装 Dev Debug 包，手动打开静态首页，检查模糊遮罩、Sheet 的 Back 安全区、按住单元时的底色、分区页和凸出扫码按钮。真实登录/后端和扫码设备未在本轮验证。
- API 36 模拟器 `gfxinfo` 对同一弹层做三次开关的观察：原 Compose 整页模糊的帧耗时中位数 93ms、90 分位 250ms；系统窗口模糊为 53ms、129ms。模拟器仍有掉帧，此数值是本机交互采样，不代表真机性能基准。
- 模拟器截图：[分娩总览](../verification/home-ui/overview-zh.png)、[单元选择与毛玻璃](../verification/home-ui/unit-sheet-zh.png)、[当前单元高亮](../verification/home-ui/unit-sheet-selected-zh.png)、[按住单元 7](../verification/home-ui/unit-sheet-pressed-zh.png)、[单元 6](../verification/home-ui/unit-6-zh.png)、[分区选择](../verification/home-ui/sections-zh.png)。单元 6 用于核对 7/18→4/8 与 4→2 今日预产的范围变化。

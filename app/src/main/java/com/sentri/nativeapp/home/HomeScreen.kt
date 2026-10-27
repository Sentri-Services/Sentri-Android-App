package com.sentri.nativeapp.home

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriIcon
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriProgress
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.design.SentriWorkTile
import com.sentri.nativeapp.design.WorkTileTodo

/** UI-only Workbench aligned to Sentri-UI features/workbench at 6df878f. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("workbench_ui_preview", android.content.Context.MODE_PRIVATE) }
    val homeView = LocalView.current
    SideEffect {
        var context = homeView.context
        while (context is ContextWrapper) {
            if (context is Activity) {
                WindowCompat.getInsetsController(context.window, context.window.decorView).isAppearanceLightStatusBars = true
                break
            }
            context = context.baseContext
        }
    }
    var sectionId by rememberSaveable { mutableStateOf(prefs.getString("section", "gestation")?.takeIf { saved -> HomePreviewData.sections.any { it.id == saved } } ?: "gestation") }
    var unit by rememberSaveable { mutableStateOf<Int?>(null) }
    var page by rememberSaveable { mutableStateOf("home") }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleFilter by rememberSaveable { mutableStateOf("production") }
    var scheduleWeek by rememberSaveable { mutableStateOf(0) }
    var scheduleRow by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleKeySeen by rememberSaveable { mutableStateOf(false) }
    var mockRefresh by rememberSaveable { mutableStateOf(0) }
    val templates = listOf(
        "normal" to "current", "many" to "busy", "due" to "clear",
        "waiting" to "health", "normal" to "feed", "normal" to "maintenance",
        "normal" to "unknown", "empty" to "current", "loading" to "current",
        "permission" to "current", "error" to "current",
    )
    val (scenario, attention) = templates[mockRefresh % templates.size]
    // Visual preview only. The actual badge state belongs to data-sync.
    val savedWorkBadge = when (mockRefresh % 3) {
        1 -> SavedWorkBadgePreview(SavedWorkBadge.Uploading, 3)
        2 -> SavedWorkBadgePreview(SavedWorkBadge.NeedsAttention, 1)
        else -> SavedWorkBadgePreview(SavedWorkBadge.Waiting, 3)
    }
    var endedEarlyId by rememberSaveable { mutableStateOf<String?>(null) }
    val section = HomePreviewData.sections.first { it.id == sectionId }
    val unknownTask = WorkItem("statusUnknown", "gestation", WorkCategory.Production, "Pregnancy check", "Batch 40", "pregnancy", listOf(7), TileState.Unknown)
    val extras = (if (scenario == "many") WorkbenchMockData.manyTasks else emptyList()) +
        (if (attention == "unknown" && sectionId == "gestation") listOf(unknownTask) else emptyList())
    val all = WorkbenchMockData.tasks + extras
    val selectedTask = all.firstOrNull { it.id == taskId }
    val openProduction = (WorkbenchMockData.production(sectionId, scenario == "many") + extras.filter { it.state == TileState.Unknown })
        .filter { it.id != endedEarlyId && scenario != "empty" }.map { task ->
            when(scenario) {
                "due" -> task.copy(state=TileState.Due,due=if(task.due>0)task.due else 4,overdue=0)
                "waiting" -> task.copy(state=TileState.Waiting,next=if(task.id=="preg28")0 else if(task.next>0)task.next else 4,overdue=0)
                else -> task
            }
        }
    val closed = WorkbenchMockData.closed(sectionId) + listOfNotNull(all.firstOrNull { it.id == endedEarlyId })
    val goBack: () -> Unit = {
        when (page) {
            "home" -> onExit()
            "task" -> page = "home"
            "sections", "schedule", "toolbox", "records" -> page = "home"
            else -> page = "home"
        }
    }
    LaunchedEffect(page) {
        if (page == "schedule" && !scheduleKeySeen) {
            scheduleKeySeen = true
            sheet = "schedule-key"
        }
    }
    BackHandler(enabled = sheet == null) { goBack() }
    Scaffold(containerColor = SentriTheme.colors.background,
        bottomBar = { if (page != "schedule" && page != "task") WorkbenchBottomBar(page == "toolbox", onHome = { page = "home" }, onToolbox = { page = "toolbox" }, onScan = { sheet = "preview" }) }
    ) { padding ->
        when (page) {
            "schedule" -> SchedulePage(sectionId, scheduleWeek, scheduleFilter,
                onClose = { page = "home" }, onWeek = { scheduleWeek = it },
                onFilter = { scheduleFilter = it }, onKey = { sheet = "schedule-key" },
                onRow = { scheduleRow = it; sheet = "schedule-row" }, modifier = Modifier.padding(padding))
            "task" -> TaskPreviewPage(selectedTask, onBack = goBack,
                onEnd = { sheet = "end-task" }, endedEarly = taskId == endedEarlyId,
                modifier = Modifier.padding(padding))
            "toolbox" -> ToolboxPage(unit, onBack = goBack, onRecords = { page = "records" },
                onSections = { page = "sections" },
                onOther = { sheet = "preview" }, modifier = Modifier.padding(padding))
            "records" -> RecordsPage(populated = mockRefresh % 2 == 1, onBack = goBack, modifier = Modifier.padding(padding))
            else -> AnimatedContent(targetState = page, modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    if (targetState == "sections")
                        (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(180)))
                    else
                        (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(180)))
                }, label = "Section navigation") { visible ->
                if (visible == "sections") SectionsPage(sectionId, all, endedEarlyId, onBack = { page = "home" }, onSelect = {
                    sectionId = it; prefs.edit().putString("section", it).apply(); unit = null; page = "home"
                }) else Column(Modifier.fillMaxSize()) {
                    Header(section, savedWorkBadge, onSection = { page = "sections" }, onSavedWork = { sheet = "saved-work" }, onAssistant = { sheet = "preview" })
                    ScopeRail(unit, section.units.size, onOverview = { unit = null }, onUnits = { sheet = "units" })
                    PullToRefreshBox(isRefreshing = false, onRefresh = { mockRefresh++ }, modifier = Modifier.fillMaxSize()) {
                        if (unit == null) OverviewContent(sectionId, openProduction, closed, scenario,
                            onTask = { taskId = it; page = "task" }, onSchedule = {
                                scheduleFilter = it; scheduleWeek = 0; page = "schedule"
                            }, onClosed = { sheet = "closed" }, onMarks = { sheet = it })
                        else UnitContent(sectionId, unit!!, openProduction.filter { unit in it.units }, closed.filter { unit in it.units }, attention, scenario,
                            onTask = { taskId = it; page = "task" }, onInspect = { sheet = "preview" },
                            onSchedule = { scheduleFilter = "production"; page = "schedule" })
                    }
                }
            }
        }
    }
    when (sheet) {
        "units" -> SentriSheet(stringResource(R.string.home_section_units, stringResource(section.name)), stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss = { sheet = null }) {
            Text(stringResource(R.string.home_all_units, section.units.size), style = captionStyle(), color = SentriTheme.colors.muted)
            Spacer(Modifier.height(12.dp))
            SentriPanel(Modifier.fillMaxWidth()) { Column {
                section.units.forEachIndexed { index, candidate ->
                    if (index > 0) Divider()
                    val facts = WorkbenchMockData.unitPopulation(sectionId, candidate)
                    val n = openProduction.count { candidate in it.units }
                    SentriRow(stringResource(R.string.home_unit_n, candidate), stringResource(R.string.home_unit_facts, facts.first, facts.second), "grid",
                        onClick = { unit = candidate; sheet = null }, menuCurrent = unit == candidate,
                        trailing = if (n == 0) stringResource(R.string.home_unit_no_tasks) else stringResource(R.string.home_unit_tasks, n))
                }
            } }
        }
        "closed" -> SentriSheet(stringResource(R.string.wb_closed_today), stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss = { sheet = null }) {
            closed.sortedByDescending { it.closedAt ?: "99:99" }.forEach { task ->
                SentriRow("${typeLabel(task.type)} · ${batchLabel(task.batch)}", if (task.id == endedEarlyId || task.endedEarly) stringResource(R.string.wb_ended_early) else stringResource(R.string.wb_completed_at, task.closedAt ?: "just now"),
                    "check", onClick = { taskId = task.id; page = "task"; sheet = null })
                Divider()
            }
        }
        "treat", "monitor" -> MarkedListSheet(sheet!!, sectionId, onDismiss = { sheet = null }, onPig = {
            unit = it; sheet = "preview-inspection"
        })
        "schedule-key" -> ScheduleKeySheet { sheet = null }
        "schedule-row" -> {
            val row = WorkbenchMockData.board(sectionId, scheduleWeek).flatMap { it.rows }.firstOrNull { it.fullName == scheduleRow }
            if (row != null) ScheduleRowSheet(row, scheduleWeek) { sheet = null }
        }
        "end-task" -> SentriSheet(stringResource(R.string.home_end_task_question), stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss = { sheet = null }) {
            Text(stringResource(R.string.home_end_task_warning), style = bodyStyle(), color = SentriTheme.colors.ink)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp)).background(SentriTheme.colors.ink)
                .combinedClickable(onClick = {}, onLongClick = { endedEarlyId = taskId; page = "home"; sheet = null }),
                contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.wb_hold_to_end_mock), style = bodyStyle(), color = Color.White)
            }
            Text(stringResource(R.string.wb_mock_end_note), Modifier.padding(top = 10.dp), style = captionStyle(), color = SentriTheme.colors.muted)
        }
        "saved-work" -> SentriSheet(stringResource(R.string.home_saved_work), stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss = { sheet = null }) {
            Text(stringResource(R.string.wb_sync_owned), style = bodyStyle(), color = SentriTheme.colors.muted)
        }
        "preview", "preview-inspection" -> SentriSheet(stringResource(R.string.home_preview_title), stringResource(R.string.home_back), stringResource(R.string.home_close), onDismiss = { sheet = null }) {
            Text(if (sheet == "preview-inspection") stringResource(R.string.wb_inspection_owned) else stringResource(R.string.home_preview_only),
                style = bodyStyle(), color = SentriTheme.colors.muted)
        }
    }
}

@Composable private fun captionStyle() = androidx.compose.ui.text.TextStyle(fontFamily = SentriType.sans, fontSize = 11.sp, lineHeight = 16.sp)
@Composable private fun bodyStyle() = androidx.compose.ui.text.TextStyle(fontFamily = SentriType.sans, fontSize = 14.sp, lineHeight = 21.sp)
@Composable private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(SentriTheme.colors.rule)) }

internal enum class SavedWorkBadge { Waiting, Uploading, NeedsAttention }
internal data class SavedWorkBadgePreview(val state: SavedWorkBadge, val count: Int)

@Composable private fun Header(section: HomeSection, savedWorkBadge: SavedWorkBadgePreview, onSection: () -> Unit, onSavedWork: () -> Unit, onAssistant: () -> Unit) {
    val c = SentriTheme.colors
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=18.dp,top=10.dp,bottom=9.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick=onSection).padding(vertical=4.dp)) {
            Text(stringResource(R.string.home_farm_sample), style=captionStyle(), color=c.muted)
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(stringResource(section.name), fontFamily=SentriType.sans,fontSize=25.sp,fontWeight=FontWeight.Bold,color=c.ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                SentriIcon("down",null,Modifier.size(16.dp),c.muted)
            }
        }
        Surface(onClick=onAssistant,shape=RoundedCornerShape(12.dp),color=c.paper,border=BorderStroke(1.dp,c.line)) {
            Row(Modifier.height(48.dp).padding(horizontal=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                SentriIcon("spark",null,Modifier.size(17.dp)); Text(stringResource(R.string.home_assistant),style=captionStyle(),color=c.ink)
            }
        }
        SavedWorkStatusBadge(savedWorkBadge, onSavedWork)
    }
}

@Composable internal fun SavedWorkStatusBadge(preview: SavedWorkBadgePreview, onClick: () -> Unit) {
    val (background, border, foreground) = when (preview.state) {
        SavedWorkBadge.Waiting -> Triple(Color(0xFFFFF9EC), Color(0xFFDECCA9), Color(0xFF70542C))
        SavedWorkBadge.Uploading -> Triple(Color(0xFFEDF4F7), Color(0xFFBED0D8), Color(0xFF31596A))
        SavedWorkBadge.NeedsAttention -> Triple(Color(0xFFFFF2EF), Color(0xFFDFB7AF), Color(0xFF893D32))
    }
    val description = when (preview.state) {
        SavedWorkBadge.Waiting -> stringResource(R.string.wb_saved_work_waiting_n, preview.count)
        SavedWorkBadge.Uploading -> stringResource(R.string.wb_saved_work_uploading_n, preview.count)
        SavedWorkBadge.NeedsAttention -> stringResource(R.string.wb_saved_work_attention_n, preview.count)
    }
    Surface(onClick=onClick, modifier=Modifier.semantics { contentDescription = description },
        shape=RoundedCornerShape(16.dp), color=background, border=BorderStroke(1.dp,border), shadowElevation=3.dp) {
        Row(Modifier.defaultMinSize(minWidth=48.dp,minHeight=48.dp).padding(horizontal=7.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            when (preview.state) {
                SavedWorkBadge.Waiting -> Text("↑",fontFamily=SentriType.sans,fontSize=17.sp,fontWeight=FontWeight.Bold,color=foreground)
                SavedWorkBadge.Uploading -> {
                    val angle by rememberInfiniteTransition(label="Saved work upload preview").animateFloat(
                        initialValue=0f,targetValue=360f,
                        animationSpec=infiniteRepeatable(tween(1800,easing=LinearEasing),RepeatMode.Restart),
                        label="Uploading rotation")
                    Text("↻",modifier=Modifier.rotate(angle),fontFamily=SentriType.sans,fontSize=17.sp,fontWeight=FontWeight.Bold,color=foreground)
                }
                SavedWorkBadge.NeedsAttention -> Text(stringResource(R.string.wb_saved_work_fix_short),fontFamily=SentriType.sans,
                    fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=foreground,maxLines=1)
            }
            Spacer(Modifier.width(3.dp))
            Text(preview.count.toString(),fontFamily=SentriType.sans,fontSize=14.sp,
                fontWeight=FontWeight.Bold,color=foreground,maxLines=1)
        }
    }
}

@Composable private fun ScopeRail(unit:Int?, count:Int, onOverview:()->Unit,onUnits:()->Unit) {
    val c=SentriTheme.colors
    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=12.dp).clip(RoundedCornerShape(15.dp)).background(c.well)
        .border(1.dp,c.line,RoundedCornerShape(15.dp)).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        ScopeButton(stringResource(R.string.home_overview),unit==null,onOverview,Modifier.weight(.40f),false)
        ScopeButton(if(unit==null)stringResource(R.string.home_choose_unit) else stringResource(R.string.home_unit_n,unit),unit!=null,onUnits,Modifier.weight(.60f),true)
    }
}
@Composable private fun ScopeButton(label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier,trailing:Boolean) {
    val c=SentriTheme.colors
    Row(modifier.height(48.dp).clip(RoundedCornerShape(11.dp)).background(if(selected)c.paper else Color.Transparent).clickable(onClick=onClick).padding(horizontal=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(trailing)Arrangement.SpaceBetween else Arrangement.Center) {
        Text(label,style=bodyStyle(),color=if(selected)c.ink else c.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(trailing)SentriIcon("down",null,Modifier.size(15.dp),c.muted)
    }
}

@Composable private fun OverviewContent(section:String,production:List<WorkItem>,closed:List<WorkItem>,scenario:String,onTask:(String)->Unit,onSchedule:(String)->Unit,onClosed:()->Unit,onMarks:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=18.dp,end=18.dp,top=6.dp,bottom=34.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
        item { SectionHeading(stringResource(R.string.wb_production),"production",onSchedule) }
        when(scenario) {
            "loading" -> item { StatePanel(stringResource(R.string.wb_loading),stringResource(R.string.wb_loading_detail)) }
            "permission" -> item { StatePanel(stringResource(R.string.wb_no_permission),stringResource(R.string.wb_no_permission_detail)) }
            "error" -> item { StatePanel(stringResource(R.string.wb_read_error),stringResource(R.string.wb_read_error_detail)) }
            "empty" -> item { StatePanel(stringResource(R.string.wb_nothing_open),"") }
            else -> {
                if(production.isEmpty())item { QuietText(stringResource(R.string.wb_nothing_open)) }
                production.chunked(2).forEach { row -> item(key="prod-${row.first().id}") {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        row.forEach { t -> Box(Modifier.weight(1f)) { ProductionTile(t,onTask) } }
                        if(row.size==1)Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(9.dp))
                } }
                if(closed.isNotEmpty())item { QuietAction(stringResource(R.string.wb_closed_count,closed.size),"check",onClosed) }
            }
        }
        item { Spacer(Modifier.height(20.dp)); SectionHeading(stringResource(R.string.wb_health),"health",onSchedule) }
        if(scenario in listOf("loading","permission","error"))item { StatePanel(
            when(scenario){"loading"->stringResource(R.string.wb_loading);"permission"->stringResource(R.string.wb_no_permission);else->stringResource(R.string.wb_read_error)},"")
        } else item { HealthSummary(section,onMarks,quiet=scenario=="empty") }
        if(scenario !in listOf("loading","permission","error","empty")) {
            val health=WorkbenchMockData.health(section)
            if(health.isNotEmpty())item { Spacer(Modifier.height(10.dp)) }
            health.chunked(2).forEach { row -> item(key="health-${row.first().id}") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    row.forEach { t -> Box(Modifier.weight(1f)) { ProductionTile(t,onTask) } }
                    if(row.size==1)Spacer(Modifier.weight(1f))
                }; Spacer(Modifier.height(9.dp))
            } }
        }
        item { Spacer(Modifier.height(20.dp)); SectionHeading(stringResource(R.string.wb_other),null,onSchedule) }
        val other=if(scenario in listOf("empty","loading","permission","error"))emptyList() else WorkbenchMockData.other(section)
        if(scenario in listOf("loading","permission","error"))item { StatePanel(
            when(scenario){"loading"->stringResource(R.string.wb_loading);"permission"->stringResource(R.string.wb_no_permission);else->stringResource(R.string.wb_read_error)},"")
        } else if(other.isEmpty())item { QuietText(stringResource(R.string.wb_nothing_open)) } else item {
            SentriPanel(Modifier.fillMaxWidth()) { Column {
                other.forEachIndexed { i,t ->
                    if(i>0)Divider()
                    OtherRow(t,onTask)
                }
            } }
        }
    }
}

@Composable private fun SectionHeading(title:String,filter:String?,onSchedule:(String)->Unit) {
    val c=SentriTheme.colors
    Row(Modifier.fillMaxWidth().padding(start=2.dp,end=2.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontFamily=SentriType.sans,fontWeight=FontWeight.SemiBold,fontSize=16.sp,color=c.ink)
        if(filter!=null)Text(stringResource(R.string.wb_schedule_link),Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSchedule(filter) }.padding(5.dp),style=captionStyle(),color=c.muted)
    }
}
@Composable private fun QuietText(text:String) { Text(text,Modifier.padding(14.dp),style=bodyStyle(),color=SentriTheme.colors.muted) }
@Composable private fun QuietAction(text:String,icon:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick=onClick).padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        SentriIcon(icon,null,Modifier.size(17.dp),SentriTheme.colors.muted)
        Text(text,Modifier.weight(1f),style=captionStyle(),color=SentriTheme.colors.muted)
        SentriIcon("chevron",null,Modifier.size(16.dp),SentriTheme.colors.muted)
    }
}
@Composable private fun StatePanel(title:String,detail:String) { SentriPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title,style=bodyStyle(),color=SentriTheme.colors.ink); if(detail.isNotEmpty())Text(detail,style=captionStyle(),color=SentriTheme.colors.muted) } } }

@Composable private fun ProductionTile(task:WorkItem,onTask:(String)->Unit) {
    val title=typeLabel(task.type)
    val todo=when(task.state) {
        TileState.Ready -> WorkTileTodo(quiet=stringResource(R.string.wb_ready_to_close),tick=true)
        TileState.Waiting -> WorkTileTodo(quiet=if(task.next>0)stringResource(R.string.wb_count_tomorrow,task.next) else stringResource(R.string.wb_none_now))
        TileState.Unknown -> WorkTileTodo(quiet=stringResource(R.string.wb_status_confirm))
        TileState.PastPlan -> WorkTileTodo(main=stringResource(R.string.wb_overdue_count,task.overdue),mainRed=false)
        TileState.PartialOverdue -> WorkTileTodo(main=stringResource(R.string.wb_count_now,task.due),red=stringResource(R.string.wb_overdue_count,task.overdue))
        TileState.Due -> WorkTileTodo(main=stringResource(when(task.type) {
            "Return heat" -> R.string.wb_count_watch
            "Farrowing" -> R.string.wb_count_farrowing
            "Processing" -> R.string.wb_count_litters
            else -> R.string.wb_count_due
        },task.due))
    }
    SentriWorkTile(title,batchLabel(task.batch),todo,task.day,task.days,task.overDays,onClick={onTask(task.id)})
}
@Composable private fun HealthSummary(section:String,onMarks:(String)->Unit,quiet:Boolean=false) {
    val treat=if(quiet)0 else when(section) {"gestation"->4;"breeding"->1;"farrowing"->2;else->0}
    val monitor=if(quiet)0 else when(section) {"gestation"->7;"breeding"->2;"farrowing"->3;else->3}
    SentriPanel(Modifier.fillMaxWidth()) { Column {
        SentriRow(stringResource(R.string.wb_to_treat),if(treat==0)stringResource(R.string.wb_nothing_marked) else if(section=="gestation")stringResource(R.string.wb_waiting_yesterday,1) else stringResource(R.string.wb_units_summary,if(section=="farrowing")"6" else "2"),
            "treat",onClick=if(treat>0){{onMarks("treat")}}else null,trailing=if(treat>0)treat.toString() else null)
        Divider()
        SentriRow(stringResource(R.string.wb_monitoring),if(monitor==0)stringResource(R.string.wb_nothing_marked) else stringResource(R.string.wb_units_summary,if(section=="gestation")"7, 8, 9" else "6, 7"),
            "monitor",onClick=if(monitor>0){{onMarks("monitor")}}else null,trailing=if(monitor>0)monitor.toString() else null)
    } }
}
@Composable private fun OtherRow(t:WorkItem,onTask:(String)->Unit) {
    val c=SentriTheme.colors
    Row(Modifier.fillMaxWidth().height(76.dp).clickable { onTask(t.id) }.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.rowIconBackground),contentAlignment=Alignment.Center) { SentriIcon(t.icon,null,Modifier.size(19.dp),c.rowIconInk) }
        Column(Modifier.weight(1f)) {
            Text(otherTitleLabel(t),style=bodyStyle(),color=c.ink,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(otherWhereLabel(t),style=captionStyle(),color=c.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(t.blocked)Text(stringResource(R.string.wb_withdrawal_block,2),style=captionStyle(),color=c.red)
        }
        Text("${t.due} ${if(t.dueUnit=="pens")stringResource(R.string.wb_pens) else stringResource(R.string.wb_pigs)}",style=captionStyle(),color=c.ink)
        SentriIcon("chevron",null,Modifier.size(14.dp),c.muted)
    }
}

@Composable private fun MarkedListSheet(kind:String,section:String,onDismiss:()->Unit,onPig:(Int)->Unit) {
    val pigs=if(section=="gestation")WorkbenchMockData.marks.filter { (kind=="treat") == it.care.isNotEmpty() } else if(kind=="treat")listOf(MarkedPig(if(section=="farrowing")6 else 2,"201744","D1","In place","Lameness")) else listOf(MarkedPig(if(section=="nursery")12 else 6,"312044","4","","Coughing"))
    SentriSheet(stringResource(if(kind=="treat")R.string.wb_to_treat else R.string.wb_monitoring),stringResource(R.string.home_back),stringResource(R.string.home_close),onDismiss) {
        pigs.groupBy { it.unit }.forEach { (unit,list) ->
            SentriSectionHeading(stringResource(R.string.home_unit_n,unit),Modifier.padding(top=8.dp,bottom=8.dp))
            SentriPanel(Modifier.fillMaxWidth()) { Column {
                list.forEachIndexed { i,p ->
                    if(i>0)Divider()
                    SentriRow(p.tag,"${stringResource(R.string.wb_pen)} ${p.pen}${if(p.care.isNotEmpty())" · ${careLabel(p.care)}" else ""}${if(p.waited)" · 1d" else ""}",null,
                        onClick={onPig(unit)},trailing=conditionLabel(p.condition),trailingTag=true)
                }
            } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun UnitContent(section:String,unit:Int,production:List<WorkItem>,closed:List<WorkItem>,attention:String,scenario:String,
    onTask:(String)->Unit,onInspect:()->Unit,onSchedule:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=18.dp,end=18.dp,top=6.dp,bottom=34.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(scenario !in listOf("permission","error"))item { UnitHub(section,unit,attention,onInspect) }
        item { Row(Modifier.fillMaxWidth().padding(top=9.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.home_todays_work),Modifier.weight(1f),fontFamily=SentriType.sans,fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=SentriTheme.colors.ink)
            Text(stringResource(R.string.home_sample_date),style=captionStyle(),color=SentriTheme.colors.muted)
        } }
        when(scenario) {
            "loading" -> item { StatePanel(stringResource(R.string.wb_loading),stringResource(R.string.wb_loading_detail)) }
            "permission" -> item { StatePanel(stringResource(R.string.wb_no_permission),stringResource(R.string.wb_no_permission_detail)) }
            "error" -> item { StatePanel(stringResource(R.string.wb_read_error),stringResource(R.string.wb_read_error_detail)) }
            else -> {
                val visible=if(scenario=="empty")emptyList() else production
                if(visible.isEmpty())item { StatePanel(stringResource(R.string.wb_no_tasks_progress),stringResource(R.string.wb_inspect_anyway)) }
                items(visible,key={"unit-${it.id}"}) { task -> UnitTaskCard(task,unit,onTask) }
                if(section!="farrowing")item { QuietAction(stringResource(R.string.wb_schedule_link),"calendar",onSchedule) }
                if(closed.isNotEmpty())item {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Column { Row(Modifier.fillMaxWidth().height(48.dp).clickable { expanded=!expanded },verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.home_closed_tasks),Modifier.weight(1f),style=bodyStyle(),color=SentriTheme.colors.muted)
                        Text(closed.size.toString(),style=captionStyle(),color=SentriTheme.colors.muted)
                        SentriIcon("down",null,Modifier.size(17.dp),SentriTheme.colors.muted)
                    }
                    if(expanded) closed.forEach { task -> ClosedUnitCard(task,onTask) }
                    }
                }
            }
        }
    }
}
@Composable private fun UnitHub(section:String,unit:Int,attention:String,onInspect:()->Unit) {
    val c=SentriTheme.colors; val facts=WorkbenchMockData.unitPopulation(section,unit)
    SentriPanel(Modifier.fillMaxWidth()) { Column {
        SentriRow(stringResource(R.string.home_inspect_unit),stringResource(R.string.home_unit_facts,facts.first,facts.second),"grid",onClick=onInspect)
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.wb_last_checked),Modifier.weight(1f),style=captionStyle(),color=c.muted)
            Text(stringResource(R.string.wb_checked_yesterday),style=captionStyle(),color=c.ink)
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
            when(attention) {
                "clear" -> { SentriIcon("check",null,Modifier.size(17.dp),c.green); Text(stringResource(R.string.wb_no_flags),style=captionStyle(),color=c.muted) }
                "unknown" -> { SentriIcon("alert",null,Modifier.size(17.dp),c.muted); Text(stringResource(R.string.wb_attention_unknown),style=captionStyle(),color=c.muted) }
                else -> {
                    val counts=when(attention) {"health"->listOf(3,0,0);"feed"->listOf(0,3,0);"maintenance"->listOf(0,0,1);"busy"->listOf(24,13,1);else->listOf(3,3,1)}
                    listOf(R.string.wb_health,R.string.wb_feed,R.string.wb_maintenance).forEachIndexed { i,label ->
                        if(counts[i]>0) Surface(shape=RoundedCornerShape(8.dp),color=c.well) { Text("${stringResource(label)} ${counts[i]}",Modifier.padding(horizontal=7.dp,vertical=6.dp),style=captionStyle(),color=c.ink) }
                    }
                }
            }
        }
    } }
    if(section!="farrowing") {
        Spacer(Modifier.height(9.dp))
        SentriPanel(Modifier.fillMaxWidth()) {
            SentriRow(stringResource(R.string.wb_environment),stringResource(R.string.wb_environment_values),"temperature",onClick=onInspect,trailing=stringResource(R.string.wb_one_device))
        }
    }
}
@Composable private fun UnitTaskCard(t:WorkItem,unit:Int,onTask:(String)->Unit) {
    val c=SentriTheme.colors; val due=t.unitDue[unit] ?: t.due
    val progress=t.unitProgress[unit]
    SentriPanel(Modifier.fillMaxWidth()) { Column(Modifier.clickable { onTask(t.id) }) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=16.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.rowIconBackground),contentAlignment=Alignment.Center) { SentriIcon(t.icon,null,Modifier.size(20.dp),c.rowIconInk) }
            Column(Modifier.weight(1f)) {
                Text(typeLabel(t.type),style=bodyStyle(),color=c.ink)
                Text(batchLabel(t.batch),style=captionStyle(),color=c.muted)
            }
            Text(if(t.overDays>0)stringResource(R.string.wb_day_overdue,t.overDays) else stringResource(R.string.wb_day_of,t.day,t.days),style=captionStyle(),color=if(t.overDays>0)c.red else c.muted)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal=17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            if(t.state!=TileState.Waiting && t.state!=TileState.Ready && t.state!=TileState.Unknown) {
                Text(due.toString(),fontFamily=SentriType.mono,fontSize=34.sp,color=c.ink)
                Text(if(t.type=="Farrowing")stringResource(R.string.wb_sows_farrowing) else stringResource(R.string.wb_due_unit),style=bodyStyle(),color=c.ink)
            } else Text(when(t.state) {
                TileState.Ready -> stringResource(R.string.wb_ready_to_close)
                TileState.Unknown -> stringResource(R.string.wb_status_confirm)
                else -> if(t.next>0)stringResource(R.string.wb_count_tomorrow,t.next) else stringResource(R.string.wb_none_now)
            },style=bodyStyle(),color=c.muted)
        }
        if(t.overdue>0 && t.state==TileState.PartialOverdue)Text(stringResource(R.string.wb_overdue_count,t.overdue),Modifier.padding(start=18.dp,top=5.dp),style=captionStyle(),color=c.red)
        if(progress!=null && progress.second>0) {
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=17.dp)) {
                Text(stringResource(if(t.type=="Processing")R.string.home_pens_done else R.string.home_checks_recorded),Modifier.weight(1f),style=captionStyle(),color=c.muted)
                Text(stringResource(R.string.home_progress,progress.first,progress.second),style=captionStyle(),color=c.ink)
            }
            SentriProgress(progress.first,progress.second,Modifier.padding(start=17.dp,end=17.dp,top=8.dp,bottom=8.dp))
        }
        Spacer(Modifier.height(13.dp))
        Divider()
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=17.dp),verticalAlignment=Alignment.CenterVertically) {
            if(t.localRecords>0)Text(stringResource(R.string.wb_local_count,t.localRecords),Modifier.weight(1f),style=captionStyle(),color=c.muted)
            else Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.home_view_task),style=captionStyle(),color=c.ink)
            SentriIcon("chevron",null,Modifier.size(15.dp),c.ink)
        }
    } }
}
@Composable private fun ClosedUnitCard(t:WorkItem,onTask:(String)->Unit) {
    SentriPanel(Modifier.fillMaxWidth()) { SentriRow("${typeLabel(t.type)} · ${batchLabel(t.batch)}",stringResource(R.string.wb_completed_at,t.closedAt ?: ""),"check",onClick={onTask(t.id)}) }
}

@Composable private fun SectionsPage(current:String,tasks:List<WorkItem>,endedEarlyId:String?,onBack:()->Unit,onSelect:(String)->Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
        PageHeader(stringResource(R.string.home_your_sections),onBack)
        Text(stringResource(R.string.home_select_section),Modifier.padding(bottom=14.dp),style=captionStyle(),color=SentriTheme.colors.muted)
        SentriPanel(Modifier.fillMaxWidth()) { Column {
            HomePreviewData.sections.forEachIndexed { i,s ->
                if(i>0)Divider()
                val count=tasks.count { it.section==s.id && it.closedAt==null && it.id!=endedEarlyId }
                SentriRow(stringResource(s.name),stringResource(R.string.wb_section_meta,s.units.size,s.population,count),s.icon,onClick={onSelect(s.id)},selected=current==s.id)
            }
        } }
    }
}
@Composable private fun PageHeader(title:String,onBack:()->Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SentriIconButton("back",stringResource(R.string.home_back),onBack)
        Text(title,fontFamily=SentriType.sans,fontSize=21.sp,fontWeight=FontWeight.SemiBold,color=SentriTheme.colors.ink)
    }
}
@Composable private fun ToolboxPage(unit:Int?,onBack:()->Unit,onRecords:()->Unit,onSections:()->Unit,onOther:()->Unit,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal=18.dp)) {
        PageHeader(stringResource(R.string.home_tab_toolbox),onBack)
        SentriPanel(Modifier.fillMaxWidth()) { Column {
            if(unit!=null) { SentriRow(stringResource(R.string.wb_maintenance),stringResource(R.string.home_unit_n,unit),"wrench",onClick=onOther); Divider() }
            SentriRow(stringResource(R.string.wb_find_pig),null,"search",onClick=onOther); Divider()
            SentriRow(stringResource(R.string.wb_records),null,"note",onClick=onRecords); Divider()
            SentriRow(stringResource(R.string.wb_sections_units),null,"barn",onClick=onSections); Divider()
            SentriRow(stringResource(R.string.home_assistant),null,"spark",onClick=onOther)
        } }
    }
}
@Composable private fun RecordsPage(populated:Boolean,onBack:()->Unit,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal=18.dp)) {
        PageHeader(stringResource(R.string.wb_records),onBack)
        if(populated)SentriPanel(Modifier.fillMaxWidth()) {
            SentriRow(stringResource(R.string.wb_record_clarification),stringResource(R.string.wb_record_context),"note",onClick=null)
        } else StatePanel(stringResource(R.string.wb_no_new_records),stringResource(R.string.wb_records_empty_detail))
        Text(stringResource(R.string.wb_records_mock_note),Modifier.padding(top=12.dp),style=captionStyle(),color=SentriTheme.colors.muted)
    }
}
@Composable private fun TaskPreviewPage(task:WorkItem?,onBack:()->Unit,onEnd:()->Unit,endedEarly:Boolean,modifier:Modifier=Modifier) {
    if(task==null)return
    val closed=task.closedAt!=null || endedEarly
    Column(modifier.fillMaxSize().padding(horizontal=18.dp)) {
        PageHeader(typeLabel(task.type),onBack)
        if(closed)StatePanel(if(endedEarly)stringResource(R.string.wb_task_ended_early) else stringResource(R.string.wb_task_complete),
            if(endedEarly)stringResource(R.string.wb_ended_early) else stringResource(R.string.wb_completed_at,task.closedAt ?: ""))
        else StatePanel(stringResource(R.string.wb_task_preview),stringResource(R.string.wb_task_preview_detail))
        Spacer(Modifier.height(12.dp))
        Text(batchLabel(task.batch),style=bodyStyle(),color=SentriTheme.colors.muted)
        Spacer(Modifier.weight(1f))
        if(!closed && task.state != TileState.Unknown)SentriButton(stringResource(R.string.home_end_task_early),onEnd,Modifier.fillMaxWidth(),primary=false)
        Text(stringResource(if(closed)R.string.home_result_not_designed else R.string.home_task_preview_only),Modifier.padding(vertical=16.dp),style=captionStyle(),color=SentriTheme.colors.muted)
    }
}

@Composable private fun SchedulePage(section:String,week:Int,filter:String,onClose:()->Unit,onWeek:(Int)->Unit,onFilter:(String)->Unit,onKey:()->Unit,onRow:(String)->Unit,modifier:Modifier=Modifier) {
    val c=SentriTheme.colors
    val dates=when(week) {-1->listOf(28,29,30,1,2,3,4);1->(12..18).toList();else->(5..11).toList()}
    val board=WorkbenchMockData.board(section,week).filter { filter=="all" || it.category.name.lowercase()==filter }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=15.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SentriIconButton("close",stringResource(R.string.home_close),onClose,bordered=false)
            Column(Modifier.weight(1f)) {
                Text(when(week){-1->"28 Jun – 4 Jul";1->"12 – 18 Jul";else->"5 – 11 Jul"},fontFamily=SentriType.sans,fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=c.ink)
                Text(stringResource(R.string.wb_week_section,week+27,stringResource(HomePreviewData.sections.first{it.id==section}.name)),style=captionStyle(),color=c.muted)
            }
            SentriIconButton("info",stringResource(R.string.wb_how_to_read),onKey)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=9.dp).clip(RoundedCornerShape(11.dp)).background(c.well).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            listOf("all","production","health","other").forEach { key ->
                Box(Modifier.weight(1f).height(39.dp).clip(RoundedCornerShape(9.dp)).background(if(filter==key)c.paper else Color.Transparent).clickable { onFilter(key) },contentAlignment=Alignment.Center) {
                    Text(filterLabel(key),style=captionStyle(),color=if(filter==key)c.ink else c.muted,maxLines=1)
                }
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=10.dp,bottom=16.dp)) {
            item { SentriPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical=8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start=9.dp,end=9.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Spacer(Modifier.width(42.dp))
                    listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").forEachIndexed { i,day ->
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(day,fontFamily=SentriType.sans,fontSize=9.sp,color=c.muted,maxLines=1)
                            Text(dates[i].toString(),fontFamily=SentriType.mono,fontSize=11.sp,color=if(week==0&&i==3)c.green else c.ink)
                        }
                    }
                }
                if(board.all { it.rows.isEmpty() })QuietText(stringResource(R.string.wb_nothing_planned))
                board.forEach { band ->
                    if(filter=="all") {
                        Divider()
                        Row(Modifier.fillMaxWidth().background(c.background).padding(horizontal=13.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            SentriIcon(when(band.category){WorkCategory.Production->"production";WorkCategory.Health->"health";WorkCategory.Other->"transfer"},null,Modifier.size(17.dp),c.rowIconInk)
                            Text(filterLabel(band.category.name.lowercase()),style=bodyStyle(),color=c.ink)
                        }
                    }
                    if(band.rows.isEmpty()&&filter=="all")QuietText(stringResource(R.string.wb_nothing_planned))
                    band.rows.groupBy { it.type }.forEach { (type,rows) ->
                        Text(typeLabel(type),Modifier.padding(start=12.dp,top=11.dp,bottom=4.dp),fontFamily=SentriType.sans,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=c.ink)
                        rows.forEach { r -> ScheduleBoardRow(r,week,onRow) }
                        Divider()
                    }
                }
            } } }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).background(c.paper).padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
            if(week>-1)Text(stringResource(R.string.wb_previous_week,week+26),Modifier.weight(1f).clickable { onWeek(week-1) },style=bodyStyle(),color=c.ink)
            else Spacer(Modifier.weight(1f))
            if(week<1)Text(stringResource(R.string.wb_next_week,week+28),Modifier.clickable { onWeek(week+1) },style=bodyStyle(),color=c.ink)
        }
    }
}
@Composable private fun ScheduleBoardRow(row:BoardRow,week:Int,onClick:(String)->Unit) {
    val c=SentriTheme.colors
    Row(Modifier.fillMaxWidth().height(48.dp).clickable { onClick(row.fullName) }.padding(horizontal=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(row.label,Modifier.width(42.dp),fontFamily=SentriType.mono,fontSize=10.sp,color=c.ink,maxLines=1,overflow=TextOverflow.Clip)
        row.cells.forEach { raw ->
            ScheduleCell(raw,Modifier.weight(1f).padding(horizontal=1.dp))
        }
    }
}
@Composable private fun ScheduleCell(raw:String,modifier:Modifier) {
    val c=SentriTheme.colors
    val code=raw.replace("<","").replace(">","")
    val state=code.firstOrNull()
    val number=code.drop(1)
    val label=when(state) { 'd','o','p','v','w' -> compactCount(number);'e'->"~${compactCount(number,true)}";'u'->"–";'n'->"·";else->"" }
    val shown=(if(raw.startsWith("<"))"‹" else "")+label+(if(raw.endsWith(">"))"›" else "")
    val bg=when(state){'d'->c.well;'o'->c.greenWash;else->Color.Transparent}
    val border=when(state){'p','u'->c.line;else->Color.Transparent}
    val cell=modifier.height(34.dp).clip(RoundedCornerShape(6.dp)).background(bg)
        .then(if(border!=Color.Transparent)Modifier.border(1.dp,border,RoundedCornerShape(6.dp))else Modifier)
        .drawBehind {
            if(state=='e')drawRoundRect(c.controlBorder,cornerRadius=CornerRadius(6.dp.toPx()),
                style=Stroke(width=1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
            if(state=='v'||state=='w')drawLine(c.red,Offset(0f,2.dp.toPx()),Offset(0f,size.height-2.dp.toPx()),strokeWidth=2.dp.toPx())
        }
    Box(cell,contentAlignment=Alignment.Center) {
        Text(shown,fontFamily=SentriType.mono,fontSize=10.sp,color=if(state=='v')c.red else if(state=='n')c.muted else c.ink,maxLines=1)
    }
}
private fun compactCount(value:String,estimate:Boolean=false):String {
    val n=value.toIntOrNull() ?: return value
    if(n<1000)return n.toString()
    if(estimate)return "${n/1000}k"
    val tenths=n/100
    return if(tenths%10==0)"${n/1000}k" else "${n/1000}.${tenths%10}k"
}
@Composable private fun ScheduleKeySheet(onDismiss:()->Unit) {
    SentriSheet(stringResource(R.string.wb_how_to_read),stringResource(R.string.home_back),stringResource(R.string.home_close),onDismiss) {
        Text(stringResource(R.string.wb_tap_row),style=bodyStyle(),color=SentriTheme.colors.ink)
        Spacer(Modifier.height(12.dp))
        listOf("d12" to R.string.wb_key_done,"o10" to R.string.wb_key_today,"p8" to R.string.wb_key_planned,"e6" to R.string.wb_key_estimate,"u" to R.string.wb_key_waiting,"v4" to R.string.wb_key_past,"n" to R.string.wb_key_none,"e4>" to R.string.wb_key_continues).forEach { (code,desc) ->
            Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal=7.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(desc),Modifier.weight(1f),style=bodyStyle(),color=SentriTheme.colors.ink)
                ScheduleCell(code,Modifier.width(49.dp))
            }
            Divider()
        }
    }
}
@Composable private fun ScheduleRowSheet(row:BoardRow,week:Int,onDismiss:()->Unit) {
    val dates=when(week){-1->listOf(28,29,30,1,2,3,4);1->(12..18).toList();else->(5..11).toList()}
    SentriSheet(typeLabel(row.type),stringResource(R.string.home_back),stringResource(R.string.home_close),onDismiss) {
        SentriSectionHeading(row.fullName,Modifier.padding(bottom=9.dp))
        row.cells.forEachIndexed { i,raw ->
            val code=raw.replace("<","").replace(">","")
            if(code.isNotEmpty()) {
                val state=code.first(); val n=code.drop(1)
                val phrase=when(state) {
                    'd','w'->stringResource(R.string.wb_row_done,n)
                    'o'->stringResource(R.string.wb_row_due,n)
                    'p'->stringResource(R.string.wb_row_planned,n)
                    'e'->stringResource(R.string.wb_row_estimated,n)
                    'u'->stringResource(R.string.wb_key_waiting)
                    'n'->stringResource(R.string.wb_key_none)
                    'v'->stringResource(R.string.wb_row_past,n)
                    else->""
                }
                SentriRow(phrase,"${listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[i]} ${dates[i]} ${if(week==-1&&i<3)"Jun" else "Jul"}",null,onClick=null)
                Divider()
            }
        }
    }
}

@Composable internal fun WorkbenchBottomBar(activeToolbox:Boolean,onHome:()->Unit,onToolbox:()->Unit,onScan:()->Unit) {
    val c=SentriTheme.colors
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp).background(c.paper)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal=27.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Top) {
            NavItem("home",stringResource(R.string.home_tab_home),!activeToolbox,onHome)
            Spacer(Modifier.width(72.dp))
            NavItem("toolbox",stringResource(R.string.home_tab_toolbox),activeToolbox,onToolbox)
        }
        Surface(onClick=onScan,modifier=Modifier.align(Alignment.TopCenter).offset(y=(-23).dp).size(68.dp),
            shape=RoundedCornerShape(22.dp),color=c.ink,border=BorderStroke(5.dp,c.background),shadowElevation=4.dp) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                SentriIcon("scan",null,Modifier.size(22.dp),c.paper)
                Text(stringResource(R.string.home_tab_scan),style=captionStyle(),color=c.paper)
            }
        }
    }
}
@Composable private fun NavItem(icon:String,label:String,selected:Boolean,onClick:()->Unit) {
    Column(Modifier.width(60.dp).height(56.dp).clickable(onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        SentriIcon(icon,null,Modifier.size(20.dp),if(selected)SentriTheme.colors.ink else SentriTheme.colors.muted)
        Text(label,style=captionStyle(),color=if(selected)SentriTheme.colors.ink else SentriTheme.colors.muted)
    }
}

@Composable private fun typeLabel(type:String):String = when(type) {
    "Heat check"->stringResource(R.string.wb_heat_check);"Breeding"->stringResource(R.string.home_breeding)
    "Return heat"->stringResource(R.string.wb_return_heat);"Pregnancy check"->stringResource(R.string.home_task_pregnancy)
    "Farrowing"->stringResource(R.string.home_task_farrowing);"Processing"->stringResource(R.string.home_task_piglet)
    "Growth check"->stringResource(R.string.home_task_growth);"Vaccination"->stringResource(R.string.wb_vaccination)
    "Treatment"->stringResource(R.string.wb_treatment);"Pig transfer"->stringResource(R.string.wb_pig_transfer)
    "Outbound"->stringResource(R.string.wb_outbound);"Sampling"->stringResource(R.string.wb_sampling);else->type
}
@Composable private fun batchLabel(batch:String):String = when {
    batch.startsWith("Batch ")->stringResource(R.string.wb_batch_n,batch.removePrefix("Batch "))
    batch.startsWith("Plan ")->stringResource(R.string.wb_plan_n,batch.removePrefix("Plan "))
    batch.startsWith("Order ")->stringResource(R.string.wb_order_n,batch.removePrefix("Order "))
    else->batch
}
@Composable private fun otherTitleLabel(t:WorkItem):String = when(t.id) {
    "ob77"->stringResource(R.string.wb_ob77_title);"ob78"->stringResource(R.string.wb_ob78_title)
    "tr241"->stringResource(R.string.wb_tr241_title);"tr242"->stringResource(R.string.wb_tr242_title)
    "s11"->stringResource(R.string.wb_s11_title);"s12"->stringResource(R.string.wb_s12_title)
    "tr250"->stringResource(R.string.wb_tr250_title);else->t.otherTitle
}
@Composable private fun otherWhereLabel(t:WorkItem):String = when(t.id) {
    "ob77"->stringResource(R.string.wb_where_7_8)
    "s11"->stringResource(R.string.wb_where_7_9)
    "ob78","s12"->stringResource(R.string.wb_where_8)
    "tr242"->stringResource(R.string.wb_from_8_moved)
    "tr241"->stringResource(R.string.wb_from_7_8)
    "tr250"->stringResource(R.string.wb_from_6)
    else->t.otherWhere
}
@Composable private fun careLabel(care:String):String = when(care) {
    "Hospital"->stringResource(R.string.wb_hospital);"In place"->stringResource(R.string.wb_in_place);else->care
}
@Composable private fun conditionLabel(condition:String):String = when(condition) {
    "Lameness"->stringResource(R.string.wb_lameness);"Fever"->stringResource(R.string.wb_fever)
    "Shoulder sore"->stringResource(R.string.wb_shoulder_sore);"Coughing"->stringResource(R.string.wb_coughing)
    "Off feed"->stringResource(R.string.wb_off_feed);"Thin"->stringResource(R.string.wb_thin)
    "Vulva discharge"->stringResource(R.string.wb_vulva_discharge);else->condition
}
@Composable private fun filterLabel(key:String):String = when(key) {
    "all"->stringResource(R.string.wb_all);"production"->stringResource(R.string.wb_production)
    "health"->stringResource(R.string.wb_health);else->stringResource(R.string.wb_other)
}

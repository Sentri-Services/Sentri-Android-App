package com.sentri.nativeapp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.DataSource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeUnitHub(state: HomeUnitOverviewState?, onInspect: () -> Unit,
    onStock: () -> Unit, onMaintenance: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SentriTheme.colors
    SentriPanel(modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 15.dp)) {
                Text(stringResource(R.string.connected_unit_summary), Modifier.weight(1f),
                    fontFamily = SentriType.sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.ink)
            }
            Text(stockLabel(state), Modifier.padding(horizontal = 15.dp, vertical = 5.dp),
                fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.ink)
            Text(checkInLabel(state), Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
            Text(stringResource(R.string.connected_unit_attention_unknown),
                Modifier.padding(start = 15.dp, end = 15.dp, bottom = 8.dp),
                fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
            if (state?.stock?.source == DataSource.CACHE || state?.lastCheckIn?.source == DataSource.CACHE ||
                state?.maintenance?.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_unit_old_snapshot),
                    Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp),
                    fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
            if (state?.stockFailed == true || state?.checkInFailed == true || state?.maintenanceFailed == true)
                SentriButton(stringResource(R.string.connected_unit_retry), onRetry,
                    Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp), primary = false)
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
            SentriRow(stringResource(R.string.connected_unit_maintenance_title),
                when {
                    state?.maintenance != null -> stringResource(R.string.connected_unit_maintenance_count,
                        state.maintenance.value.open.size)
                    state == null || state.maintenanceLoading -> stringResource(R.string.connected_unit_maintenance_loading)
                    else -> stringResource(R.string.connected_unit_maintenance_failed)
                }, "wrench", onMaintenance)
            SentriRow(stringResource(R.string.home_inspect_unit), null, "grid", onInspect)
            SentriRow(stringResource(R.string.connected_unit_stock_entry),
                stringResource(R.string.connected_unit_stock_read_only), "grid", onStock)
        }
    }
}

@Composable
fun HomeUnitMaintenanceDetail(state: HomeUnitOverviewState?, onRetry: () -> Unit,
    onOpenFault: (com.sentri.nativeapp.inspection.DeviceInspection) -> Unit = {}) {
    val colors = SentriTheme.colors
    Text(stringResource(R.string.connected_unit_maintenance_scope), Modifier.padding(bottom = 12.dp),
        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.maintenanceLoading ->
            Text(stringResource(R.string.connected_unit_maintenance_loading), color = colors.muted)
        state.maintenance == null -> {
            Text(stringResource(R.string.connected_unit_maintenance_failed), color = colors.muted)
            SentriButton(stringResource(R.string.connected_unit_retry), onRetry,
                Modifier.fillMaxWidth().padding(top = 12.dp), primary = false)
        }
        else -> {
            if (state.maintenance.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_unit_old_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            val open = state.maintenance.value.open.sortedWith(compareBy({ it.penCode }, { it.id }))
            Text(stringResource(R.string.connected_unit_maintenance_count, open.size),
                Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            if (open.isEmpty()) Text(stringResource(R.string.connected_unit_maintenance_none), color = colors.muted)
            else SentriPanel(Modifier.fillMaxWidth()) {
                Column { open.forEach { issue ->
                    val name = issue.deviceName?.takeIf(String::isNotBlank) ?: when (issue.deviceType) {
                        "Feed" -> stringResource(R.string.inspection_pen_fault_feed)
                        "Water" -> stringResource(R.string.inspection_pen_fault_water)
                        else -> stringResource(R.string.inspection_pen_fault_other)
                    }
                    SentriRow(name, stringResource(R.string.connected_unit_pen_code, issue.penCode),
                        "wrench", { onOpenFault(issue) })
                } }
            }
        }
    }
}

@Composable
fun HomeUnitStockDetail(state: HomeUnitOverviewState?, onRetry: () -> Unit,
    onOpenPen: ((String) -> Unit)? = null) {
    val colors = SentriTheme.colors
    Text(stringResource(R.string.connected_unit_stock_detail_title), Modifier.padding(bottom = 12.dp),
        fontFamily = SentriType.sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    Text(stringResource(R.string.connected_unit_stock_scope),
        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.penLoading -> Text(stringResource(R.string.connected_unit_pen_loading),
            Modifier.padding(top = 14.dp), color = colors.muted)
        state.penFailed || state.penRoster == null -> {
            Text(stringResource(R.string.connected_unit_pen_unavailable),
                Modifier.padding(top = 14.dp), color = colors.muted)
            SentriButton(stringResource(R.string.connected_unit_retry), onRetry,
                Modifier.fillMaxWidth().padding(top = 14.dp), primary = false)
        }
        else -> {
            val roster = state.penRoster
            Text(stringResource(R.string.connected_unit_stock_numbers, roster.value.total, roster.value.pens.size),
                Modifier.padding(top = 14.dp, bottom = 10.dp), fontFamily = SentriType.sans,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            if (roster.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_unit_old_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            if (roster.value.total == 0) Text(stringResource(R.string.connected_unit_pen_empty),
                color = colors.muted)
            if (roster.value.pens.isNotEmpty()) {
                var shown by rememberSaveable(state.unitId) { mutableIntStateOf(20) }
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column {
                        roster.value.pens.take(shown).forEach { pen ->
                            SentriRow(if (pen.number > 0) stringResource(R.string.connected_unit_pen_number, pen.number)
                                else stringResource(R.string.connected_unit_pen_code, pen.code),
                                pen.code, null, onOpenPen?.takeIf {
                                    roster.value.registeredPens?.any { it.code == pen.code } == true
                                }?.let { open -> { open(pen.code) } },
                                trailing = stringResource(R.string.connected_unit_pen_pigs, pen.pigs))
                        }
                    }
                }
                if (shown < roster.value.pens.size) SentriButton(
                    stringResource(R.string.connected_unit_pen_more, roster.value.pens.size - shown),
                    { shown += 20 }, Modifier.fillMaxWidth().padding(top = 12.dp), primary = false)
            }
            val occupied = roster.value.pens.map(HomePenCount::code).toSet()
            val emptyPens = roster.value.registeredPens.orEmpty().filter { it.code != null && it.code !in occupied }
            if (roster.value.registeredPens == null) Text(stringResource(R.string.connected_unit_empty_pens_unavailable),
                Modifier.padding(top = 12.dp), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (emptyPens.isNotEmpty()) {
                Text(stringResource(R.string.connected_unit_empty_pens, emptyPens.size),
                    Modifier.padding(top = 16.dp, bottom = 8.dp), fontFamily = SentriType.sans,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                var shownEmpty by rememberSaveable(state.unitId) { mutableIntStateOf(20) }
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column {
                        emptyPens.take(shownEmpty).forEach { pen ->
                            SentriRow(if (pen.number > 0) stringResource(R.string.connected_unit_pen_number, pen.number)
                                else stringResource(R.string.connected_unit_pen_code, requireNotNull(pen.code)),
                                pen.code, null, onOpenPen?.let { open -> { open(requireNotNull(pen.code)) } },
                                trailing = stringResource(R.string.connected_unit_empty_pen_count))
                        }
                    }
                }
                if (shownEmpty < emptyPens.size) SentriButton(
                    stringResource(R.string.connected_unit_pen_more, emptyPens.size - shownEmpty),
                    { shownEmpty += 20 }, Modifier.fillMaxWidth().padding(top = 12.dp), primary = false)
            }
            val withoutCode = roster.value.registeredPens?.count { it.code == null } ?: 0
            if (withoutCode > 0) Text(stringResource(R.string.connected_unit_pens_without_code, withoutCode),
                Modifier.padding(top = 12.dp), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (roster.value.unassigned > 0) Text(
                stringResource(R.string.connected_unit_pen_unassigned, roster.value.unassigned),
                Modifier.padding(top = 12.dp), fontFamily = SentriType.sans, fontSize = 12.sp,
                color = colors.muted)
        }
    }
}

@Composable private fun stockLabel(state: HomeUnitOverviewState?): String = when {
    state == null || state.stockLoading -> stringResource(R.string.connected_unit_stock_loading)
    state.stock != null -> stringResource(R.string.connected_unit_stock_numbers,
        state.stock.value.pigCount, state.stock.value.occupiedPens)
    else -> stringResource(R.string.connected_unit_stock_unavailable)
}

@Composable private fun checkInLabel(state: HomeUnitOverviewState?): String = when {
    state == null || state.checkInLoading -> stringResource(R.string.connected_unit_check_loading)
    state.lastCheckIn == null -> stringResource(R.string.connected_unit_check_unavailable)
    state.lastCheckIn.value == null -> stringResource(R.string.connected_unit_check_none)
    state.lastCheckIn.value.createdAt == null -> stringResource(R.string.connected_unit_check_time_unknown)
    else -> stringResource(R.string.connected_unit_check_time,
        homeLocalTime(state.lastCheckIn.value.createdAt))
}

internal fun homeLocalTime(value: String): String = runCatching {
    val millis = TimestampInstant.seconds(value).movePointRight(3).toLong()
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(millis))
}.getOrDefault(value)

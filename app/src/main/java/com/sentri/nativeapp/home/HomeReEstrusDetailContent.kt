package com.sentri.nativeapp.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ReEstrusPig
import com.sentri.nativeapp.production.ReEstrusStatus
import com.sentri.nativeapp.production.ReEstrusTask
import com.sentri.nativeapp.production.TaskPigReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Reject stale Home membership and unit attribution before showing a task detail. */
internal fun consistentReEstrusDetail(catalog: HomeScopedTask, detail: ReEstrusTask,
    places: HomePlaces, nowMillis: Long): HomeScopedTask? {
    if (catalog.task.kind != ProductionTaskKind.RE_ESTRUS || detail.id != catalog.task.id ||
        detail.farmId != catalog.task.farmId || detail.batchId != catalog.task.batch.id) return null
    val freshPigs = detail.pigs.map { pig -> TaskPigReference(pig.id, pig.disabled, pig.earTag,
        pig.earNotch, pig.location.locationCode, null) }
    val fresh = HomeTaskScopeProjector.project(listOf(catalog.task.copy(pigs = freshPigs)),
        places, nowMillis).visible.singleOrNull() ?: return null
    return fresh.takeIf { it.pigUnitIds == catalog.pigUnitIds }
}

@Composable
internal fun HomeReEstrusDetailContent(task: HomeScopedTask, places: HomePlaces,
    allowedUnitIds: Set<String>, state: HomeReEstrusDetailState?, onRefresh: () -> Unit) {
    val colors = SentriTheme.colors
    Text(stringResource(R.string.connected_batch, task.task.batch.serial),
        fontFamily = SentriType.sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        color = colors.ink)
    Text(stringResource(R.string.connected_reestrus_detail_read_only),
        Modifier.padding(top = 8.dp, bottom = 12.dp), fontFamily = SentriType.sans,
        fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.taskId != task.task.id || state.loading ->
            Text(stringResource(R.string.connected_reestrus_loading), color = colors.muted)
        state.failed -> {
            Text(stringResource(R.string.connected_reestrus_error), color = colors.muted)
            SentriButton(stringResource(R.string.connected_pregnancy_retry), onRefresh,
                Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
        }
        state.result?.value == null ->
            Text(stringResource(R.string.connected_reestrus_removed), color = colors.muted)
        else -> {
            val result = state.result
            val detail = result.value
            val scoped = consistentReEstrusDetail(task, detail, places, System.currentTimeMillis())
            if (scoped == null) {
                Text(stringResource(R.string.connected_reestrus_scope_changed), color = colors.muted)
                return
            }
            if (result.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_offline_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            val selected = detail.pigs.filter { !it.disabled && scoped.pigUnitIds[it.id] in allowedUnitIds }
                .sortedWith(compareBy<ReEstrusPig> { scoped.pigUnitIds[it.id] }
                    .thenBy { it.location.penLocation ?: "" }.thenBy { it.id })
            Text(stringResource(R.string.connected_reestrus_pig_count, selected.size),
                Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            var shown by rememberSaveable(task.task.id, allowedUnitIds) { mutableIntStateOf(30) }
            val units = places.sections.flatMap(HomePlaceSection::units).associateBy { it.id }
            val chinese = LocalConfiguration.current.locales[0].language == "zh"
            selected.take(shown).groupBy { scoped.pigUnitIds[it.id] to it.location.penLocation }
                .forEach { (place, pigs) ->
                    val unit = units[place.first]
                    val unitName = unit?.let { if (chinese) it.nameCn.ifBlank { it.nameEn }
                        else it.nameEn.ifBlank { it.nameCn } }
                        ?: stringResource(R.string.connected_estrus_unit_unknown)
                    val pen = place.second?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.connected_estrus_pen_unknown)
                    SentriSectionHeading(stringResource(R.string.connected_estrus_place, unitName, pen))
                    SentriPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column {
                            pigs.forEach { pig ->
                                val time = pig.result?.checkedAt ?: pig.result?.submittedAt
                                SentriRow(reEstrusPigLabel(pig), time?.let {
                                    stringResource(R.string.connected_reestrus_recorded_at, localReEstrusTime(it))
                                }, null, null, trailing = reEstrusStatusLabel(pig), trailingTag = true)
                            }
                        }
                    }
                }
            if (shown < selected.size) SentriButton(
                stringResource(R.string.connected_reestrus_more, selected.size - shown),
                { shown += 30 }, Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
        }
    }
}

@Composable private fun reEstrusPigLabel(pig: ReEstrusPig): String =
    pig.earTag?.takeIf(String::isNotBlank) ?: pig.earNotch?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.connected_estrus_pig_unknown)

@Composable private fun reEstrusStatusLabel(pig: ReEstrusPig): String = stringResource(when {
    pig.result?.isInReEstrus == true -> R.string.connected_reestrus_status_submitted
    pig.result?.status == ReEstrusStatus.RE_ESTRUS -> R.string.connected_reestrus_status_returned
    pig.result?.status == ReEstrusStatus.SIGNS_OF_RE_ESTRUS -> R.string.connected_reestrus_status_signs
    pig.result?.status == ReEstrusStatus.NOT_RE_ESTRUS -> R.string.connected_reestrus_status_not_returned
    else -> R.string.connected_reestrus_status_unrecorded
})

private fun localReEstrusTime(value: String): String = runCatching {
    val millis = TimestampInstant.seconds(value).movePointRight(3).toLong()
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(millis))
}.getOrDefault(value)

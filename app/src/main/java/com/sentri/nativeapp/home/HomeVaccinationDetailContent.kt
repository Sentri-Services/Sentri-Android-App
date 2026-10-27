package com.sentri.nativeapp.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.vaccination.VaccinationTask
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Never combine a refreshed dose history with the old Home task's unit scope. */
internal fun consistentVaccinationDetail(catalog: HomeScopedVaccination, detail: VaccinationTask,
    places: HomePlaces, nowMillis: Long): HomeScopedVaccination? {
    if (detail.id != catalog.task.id || detail.farmId != catalog.task.farmId || detail.isDeleted ||
        detail.vaccineId != catalog.task.vaccineId || detail.brandId != catalog.task.brandId ||
        detail.targets.map { it.targetId }.toSet() != catalog.pigUnitIds.keys) return null
    val fresh = HomeVaccinationScopeProjector.project(listOf(detail.copy(status = "IN_PROGRESS")),
        places, nowMillis).visible.singleOrNull() ?: return null
    return fresh.takeIf { it.pigUnitIds == catalog.pigUnitIds }
}

@Composable
internal fun HomeVaccinationDetailContent(catalog: HomeScopedVaccination, places: HomePlaces,
    allowedUnitIds: Set<String>, state: HomeVaccinationDetailState?, onRefresh: () -> Unit) {
    val colors = SentriTheme.colors
    if (state == null || state.taskId != catalog.task.id) {
        DetailMessage(R.string.connected_vaccination_detail_loading)
        return
    }
    if (state.loading) DetailMessage(R.string.connected_vaccination_detail_loading)
    if (state.failed) {
        DetailMessage(R.string.connected_vaccination_detail_error)
        SentriButton(stringResource(R.string.connected_pregnancy_retry), onRefresh, Modifier.fillMaxWidth(),
            primary = false)
    }
    val result = state.result ?: return
    val detail = result.value
    if (detail == null) {
        DetailMessage(R.string.connected_vaccination_detail_removed)
        return
    }
    val scoped = consistentVaccinationDetail(catalog, detail, places, System.currentTimeMillis())
    if (scoped == null) {
        DetailMessage(R.string.connected_vaccination_detail_scope_changed)
        return
    }
    if (result.source == DataSource.CACHE) DetailMessage(R.string.connected_vaccination_detail_offline)
    DetailMessage(R.string.connected_vaccination_detail_read_only)
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val vaccine = detail.vaccine?.let { if (chinese) it.chinese.ifBlank { it.english }
        else it.english.ifBlank { it.chinese } }
    Text(vaccine?.takeIf(String::isNotBlank) ?: stringResource(R.string.connected_vaccination_name_unknown),
        fontFamily = SentriType.sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    val brand = detail.brand?.let { if (chinese) it.chinese.ifBlank { it.english }
        else it.english.ifBlank { it.chinese } }
    brand?.takeIf(String::isNotBlank)?.let { Text(it, fontFamily = SentriType.sans, fontSize = 13.sp,
        color = colors.muted) }
    Text(stringResource(R.string.connected_vaccination_detail_dose,
        detail.dose.toPlainString(), detail.doseUnit), fontFamily = SentriType.sans,
        fontSize = 13.sp, color = colors.muted)
    Text(vaccinationTaskStatusLabel(detail.status), fontFamily = SentriType.sans,
        fontSize = 13.sp, color = colors.muted)
    detail.scheduledAt?.let { Text(stringResource(R.string.connected_vaccination_planned_time,
        localVaccinationTime(it)), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted) }
    val targetIds = scoped.pigUnitIds.filterValues { it in allowedUnitIds }.keys
    val targets = detail.targets.groupBy { it.targetId }.filterKeys { it in targetIds }
    SentriSectionHeading(stringResource(R.string.connected_vaccination_detail_targets),
        meta = targetIds.size.toString())
    targets.toSortedMap().forEach { (targetId, doses) ->
        val pig = doses.first().pig
        val identity = pig?.earTag?.takeIf(String::isNotBlank)
            ?: pig?.earNotch?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.connected_vaccination_pig_id, targetId)
        SentriPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(identity, fontFamily = SentriType.sans, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.ink)
                doses.sortedBy { it.doseIndex }.forEach { dose ->
                    Text(stringResource(R.string.connected_vaccination_dose_row,
                        dose.doseIndex, vaccinationDoseStatusLabel(dose.status)),
                        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
                    dose.scheduledAt?.let { Text(stringResource(R.string.connected_vaccination_planned_time,
                        localVaccinationTime(it)), fontFamily = SentriType.sans,
                        fontSize = 11.sp, color = colors.muted) }
                    dose.executedAt?.let { Text(stringResource(R.string.connected_vaccination_recorded_time,
                        localVaccinationTime(it)), fontFamily = SentriType.sans,
                        fontSize = 11.sp, color = colors.muted) }
                }
            }
        }
    }
}

@Composable private fun DetailMessage(resource: Int) {
    Text(stringResource(resource), Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
        fontSize = 12.sp, color = SentriTheme.colors.muted)
}

@Composable private fun vaccinationTaskStatusLabel(value: String): String = stringResource(when (value) {
    "PENDING" -> R.string.connected_vaccination_task_pending
    "IN_PROGRESS" -> R.string.biz_vaccination_status_in_progress
    "COMPLETED" -> R.string.biz_vaccination_status_completed
    "DELETED" -> R.string.biz_vaccination_status_deleted
    else -> R.string.connected_vaccination_status_unknown
})

@Composable private fun vaccinationDoseStatusLabel(value: String): String = stringResource(when (value) {
    "PENDING" -> R.string.connected_vaccination_dose_unrecorded
    "IN_PROGRESS" -> R.string.biz_vaccination_status_in_progress
    "COMPLETED" -> R.string.connected_vaccination_dose_recorded
    "DELETED" -> R.string.biz_vaccination_status_deleted
    else -> R.string.connected_vaccination_status_unknown
})

private fun localVaccinationTime(value: String): String = runCatching {
    val millis = TimestampInstant.seconds(value).movePointRight(3).toLong()
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(millis))
}.getOrDefault(value)

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
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.production.FarrowingTask
import com.sentri.nativeapp.production.GrowingTask
import com.sentri.nativeapp.production.LactationTask
import com.sentri.nativeapp.production.PigletTask
import com.sentri.nativeapp.production.PostpartumTask
import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskSnapshot
import com.sentri.nativeapp.production.TaskPigReference

internal data class OtherProductionPig(val id: String, val disabled: Boolean,
    val earTag: String?, val earNotch: String?, val locationCode: String?, val unitId: String?, val penCode: String?) {
    fun reference() = TaskPigReference(id, disabled, earTag, earNotch, locationCode, null)
}

private fun detailPigs(detail: ProductionTaskSnapshot): List<OtherProductionPig>? = when (detail) {
    is FarrowingTask -> detail.pigs.map { OtherProductionPig(it.id, it.disabled, it.earTag,
        it.earNotch, it.location.locationCode, it.location.unitId, it.location.penLocation) }
    is PigletTask -> detail.pigs.map { OtherProductionPig(it.id, it.disabled, it.earTag,
        it.earNotch, it.location.locationCode, it.location.unitId, it.location.penLocation) }
    is PostpartumTask -> detail.pigs.map { OtherProductionPig(it.id, it.disabled, it.earTag,
        it.earNotch, it.location.locationCode, it.location.unitId, it.location.penLocation) }
    is LactationTask -> detail.pigs.map { OtherProductionPig(it.id, it.disabled, it.earTag,
        it.earNotch, it.location.locationCode, it.location.unitId, it.location.penLocation) }
    is GrowingTask -> detail.pigs.map { OtherProductionPig(it.id, it.disabled, it.earTag,
        it.earNotch, it.location.locationCode, it.location.unitId, it.location.penLocation) }
    else -> null
}

/** Re-scope fresh typed members; a changed batch, status or pig location requires a Home refresh. */
internal fun consistentOtherProductionDetail(catalog: HomeScopedTask, detail: ProductionTaskSnapshot,
    places: HomePlaces, nowMillis: Long): Pair<HomeScopedTask, List<OtherProductionPig>>? {
    if (catalog.task.kind !in setOf(ProductionTaskKind.FARROWING, ProductionTaskKind.PIGLET_PROCESSING,
            ProductionTaskKind.POSTPARTUM, ProductionTaskKind.LACTATION,
            ProductionTaskKind.NURSERY, ProductionTaskKind.FATTENING) ||
        detail.id != catalog.task.id || detail.farmId != catalog.task.farmId ||
        detail.batchId != catalog.task.batch.id || detail.type != catalog.task.kind.name ||
        detail.status != catalog.task.status.name) return null
    val pigs = detailPigs(detail) ?: return null
    val scoped = HomeTaskScopeProjector.project(listOf(catalog.task.copy(pigs = pigs.map(OtherProductionPig::reference))),
        places, nowMillis).visible.singleOrNull() ?: return null
    return (scoped to pigs).takeIf { scoped.pigUnitIds == catalog.pigUnitIds &&
        pigs.filterNot(OtherProductionPig::disabled).all { it.unitId == null || scoped.pigUnitIds[it.id] == it.unitId } }
}

/** Safe read-only doorway for the six task kinds whose execution views are still being designed. */
@Composable
internal fun HomeOtherProductionDetailContent(task: HomeScopedTask, places: HomePlaces,
    allowedUnitIds: Set<String>, state: HomeOtherProductionDetailState?, onRefresh: () -> Unit) {
    val colors = SentriTheme.colors
    Text(stringResource(R.string.connected_batch, task.task.batch.serial),
        fontFamily = SentriType.sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    Text(stringResource(R.string.connected_other_detail_read_only),
        Modifier.padding(top = 8.dp, bottom = 12.dp), fontFamily = SentriType.sans,
        fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.taskId != task.task.id || state.kind != task.task.kind || state.loading ->
            Text(stringResource(R.string.connected_other_detail_loading), color = colors.muted)
        state.failed -> {
            Text(stringResource(R.string.connected_other_detail_failed), color = colors.muted)
            SentriButton(stringResource(R.string.connected_pregnancy_retry), onRefresh,
                Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
        }
        state.result?.value == null -> Text(stringResource(R.string.connected_other_detail_missing), color = colors.muted)
        else -> {
            val result = state.result
            val consistent = consistentOtherProductionDetail(task, result.value, places, System.currentTimeMillis())
            if (consistent == null) {
                Text(stringResource(R.string.connected_other_detail_changed), color = colors.muted)
                return
            }
            if (result.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_offline_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            val (scoped, allPigs) = consistent
            val pigs = allPigs.filter { !it.disabled && scoped.pigUnitIds[it.id] in allowedUnitIds }
                .sortedWith(compareBy<OtherProductionPig> { scoped.pigUnitIds[it.id] }
                    .thenBy { it.penCode ?: "" }.thenBy { it.id })
            Text(stringResource(R.string.connected_other_detail_member_count, pigs.size),
                Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (pigs.isEmpty()) Text(stringResource(R.string.connected_other_detail_empty), color = colors.muted)
            else {
                var shown by rememberSaveable(task.task.id, allowedUnitIds) { mutableIntStateOf(30) }
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column { pigs.take(shown).forEach { pig ->
                        SentriRow(pig.earTag?.takeIf(String::isNotBlank)
                            ?: pig.earNotch?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.connected_other_detail_untagged),
                            pig.penCode?.let { stringResource(R.string.connected_other_detail_pen, it) },
                            null, null)
                    } }
                }
                if (shown < pigs.size) SentriButton(
                    stringResource(R.string.connected_pregnancy_more, pigs.size - shown),
                    { shown += 30 }, Modifier.fillMaxWidth().padding(top = 12.dp), primary = false)
            }
        }
    }
}

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
import com.sentri.nativeapp.design.SentriSectionHeading
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.pigs.DataSource

/** Route and task identity must still match Home before showing records from a separate query. */
internal fun consistentTransferDetail(catalog: HomeScopedTransfer, detail: HomeTransferDetail): Boolean =
    catalog.task.id == detail.id && catalog.task.farmId == detail.farmId &&
        catalog.task.status == detail.status && catalog.task.batchSerial == detail.batchSerial &&
        catalog.task.fromUnitCodes.toSet() == detail.from.map { it.code }.toSet() &&
        catalog.task.toUnitCodes.toSet() == detail.to.map { it.code }.toSet()

@Composable
internal fun HomeTransferDetailContent(task: HomeScopedTransfer, places: HomePlaces?,
    state: HomeTransferDetailState?, onRefresh: () -> Unit) {
    val colors = SentriTheme.colors
    Text(transferRoute(task, places), fontFamily = SentriType.sans,
        fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = colors.ink)
    task.task.batchSerial?.let { Text(stringResource(R.string.connected_batch, it),
        Modifier.padding(top = 4.dp), fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted) }
    Text(stringResource(R.string.connected_transfer_read_only), Modifier.padding(top = 8.dp, bottom = 12.dp),
        fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.taskId != task.task.id || state.loading ->
            Text(stringResource(R.string.connected_transfer_detail_loading), color = colors.muted)
        state.failed -> {
            Text(stringResource(R.string.connected_transfer_detail_error), color = colors.muted)
            SentriButton(stringResource(R.string.connected_pregnancy_retry), onRefresh,
                Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
        }
        state.result?.value == null ->
            Text(stringResource(R.string.connected_transfer_detail_removed), color = colors.muted)
        !consistentTransferDetail(task, state.result.value) ->
            Text(stringResource(R.string.connected_transfer_detail_changed), color = colors.muted)
        else -> {
            val detail = state.result.value
            if (state.result.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_offline_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            if (detail.from.isNotEmpty()) {
                SentriSectionHeading(stringResource(R.string.connected_transfer_sources))
                SentriPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column { detail.from.forEach { unit ->
                        SentriRow(transferUnitName(unit.code, places), null, null, null,
                            trailing = unit.recorded?.let { stringResource(R.string.connected_transfer_recorded_out, it) }
                                ?: stringResource(R.string.connected_transfer_recorded_unknown), trailingTag = true)
                    } }
                }
            }
            if (detail.to.isNotEmpty()) {
                SentriSectionHeading(stringResource(R.string.connected_transfer_destinations))
                SentriPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column { detail.to.forEach { unit ->
                        SentriRow(transferUnitName(unit.code, places), null, null, null,
                            trailing = unit.recorded?.let { stringResource(R.string.connected_transfer_recorded_in, it) }
                                ?: stringResource(R.string.connected_transfer_recorded_unknown), trailingTag = true)
                    } }
                }
            }
            SentriSectionHeading(stringResource(R.string.connected_transfer_pigs, detail.pigs.size))
            if (detail.pigs.isEmpty()) Text(stringResource(R.string.connected_transfer_no_pigs),
                Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
            var shown by rememberSaveable(task.task.id) { mutableIntStateOf(30) }
            if (detail.pigs.isNotEmpty()) SentriPanel(Modifier.fillMaxWidth()) {
                Column { detail.pigs.take(shown).forEach { pig ->
                    SentriRow(pig.earTag?.takeIf(String::isNotBlank)
                        ?: pig.earNotch?.takeIf(String::isNotBlank) ?: stringResource(R.string.connected_transfer_untagged),
                        null, null, null, trailing = transferPigStatus(pig.status), trailingTag = true)
                } }
            }
            if (shown < detail.pigs.size) SentriButton(
                stringResource(R.string.connected_transfer_more, detail.pigs.size - shown),
                { shown += 30 }, Modifier.fillMaxWidth().padding(top = 10.dp), primary = false)
        }
    }
}

@Composable private fun transferUnitName(code: String, places: HomePlaces?): String =
    places?.sections?.flatMap(HomePlaceSection::units)?.firstOrNull { it.locationCode == code }
        ?.let { unitName(it) } ?: code

@Composable private fun transferPigStatus(status: String): String = when (status) {
    "PENDING" -> stringResource(R.string.connected_transfer_pig_pending)
    "TRANSFER_OUT" -> stringResource(R.string.connected_transfer_pig_out)
    "TRANSFER_IN" -> stringResource(R.string.connected_transfer_pig_in)
    "CANCELLED" -> stringResource(R.string.connected_transfer_pig_cancelled)
    else -> stringResource(R.string.connected_transfer_pig_unknown)
}

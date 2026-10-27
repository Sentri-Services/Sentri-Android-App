package com.sentri.nativeapp.inspection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriStepper
import com.sentri.nativeapp.design.SentriStepperFace
import com.sentri.nativeapp.design.SentriField
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType

/** Preview existing groups without creating, moving or deleting any individual pig. */
@Composable
internal fun PenCountPreviewSheet(draft: PenCountPreview, batchNames: Map<String, String>,
    stale: Boolean, offline: Boolean, onChange: (PenCountPreview) -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit,
    batchesLoading: Boolean = false, batchesFailed: Boolean = false,
    batchesOffline: Boolean = false, onBatchesRetry: () -> Unit = {}) {
    val colors = SentriTheme.colors
    fun editable(batch: String?) = batch == null || !batchNames[batch].isNullOrBlank()
    val unknown = draft.quantities.keys.count { !editable(it) }
    SentriSheet(stringResource(R.string.inspection_count_title), stringResource(R.string.home_back),
        stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.baseline.locationCode, fontFamily = SentriType.mono, fontSize = 13.sp, color = colors.muted)
            Text(stringResource(R.string.inspection_count_preview), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (offline) Text(stringResource(R.string.inspection_pen_inventory_offline),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (stale) {
                Text(stringResource(R.string.inspection_count_changed), fontFamily = SentriType.sans,
                    fontSize = 13.sp, color = colors.muted)
                SentriButton(stringResource(R.string.inspection_count_reload), onReset, Modifier.fillMaxWidth())
                return@Column
            }
            val totalTitle = stringResource(R.string.inspection_count_total)
            if (draft.quantities.size == 1 && unknown == 0) CountPreviewStepper(totalTitle, draft.totalCount,
                draft.identifiedCount, Int.MAX_VALUE, { onChange(draft.setSingleTotal(it)) },
                draftDelta = draft.totalCount - draft.baseline.totalCount, primary = true)
            else SentriPanel(Modifier.fillMaxWidth()) {
                SentriRow(totalTitle, null, null, null, trailing = draft.totalCount.toString())
            }
            Text(stringResource(R.string.inspection_count_floor, draft.identifiedCount),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (draft.quantities.isEmpty()) Text(stringResource(R.string.inspection_count_no_groups),
                fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
            if (batchesLoading) Text(stringResource(R.string.inspection_count_batches_loading),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (batchesOffline) Text(stringResource(R.string.inspection_count_batches_offline),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (batchesFailed) {
                Text(stringResource(R.string.inspection_count_batches_failed),
                    fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            }
            if (!batchesLoading && (batchesFailed || unknown > 0))
                SentriButton(stringResource(R.string.inspection_count_recheck_details), onBatchesRetry,
                    Modifier.fillMaxWidth(), primary = false)
            if (unknown > 0) Text(stringResource(R.string.inspection_count_unknown_batches, unknown),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            draft.quantities.forEach { (batch, count) ->
                val label = if (batch == null) stringResource(R.string.inspection_count_unassigned)
                    else batchNames[batch]?.let { stringResource(R.string.inspection_count_batch, it) }
                        ?: stringResource(R.string.inspection_count_unknown_batch)
                if (draft.quantities.size > 1 && editable(batch))
                    CountPreviewStepper(label, count, 0, Int.MAX_VALUE - draft.totalCount + count,
                        { onChange(draft.setQuantity(batch, it)) },
                        draftDelta = count - (draft.baseline.nonIdentityByBatch[batch] ?: 0))
                else SentriPanel(Modifier.fillMaxWidth()) {
                    SentriRow(label, null, null, null, trailing = count.toString())
                }
            }
            Text(stringResource(R.string.inspection_count_batch_actions_pending), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (draft.changed) {
                Text(stringResource(R.string.inspection_count_delta, draft.baseline.totalCount, draft.totalCount),
                    fontFamily = SentriType.sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.ink)
                Text(stringResource(R.string.inspection_count_reason), fontFamily = SentriType.sans,
                    fontSize = 13.sp, color = colors.muted)
                SentriPanel(Modifier.fillMaxWidth()) { Column {
                    draft.reasons.forEach { reason ->
                        SentriRow(countReasonLabel(reason, draft.delta), null, null,
                            { onChange(draft.copy(reason = reason)) }, selected = draft.reason == reason)
                    }
                } }
                if (draft.reason != null) SentriButton(stringResource(R.string.inspection_count_clear_reason),
                    { onChange(draft.copy(reason = null)) }, Modifier.fillMaxWidth(), primary = false)
                if (draft.reason == PenCountReason.OTHER) SentriField(draft.otherReason,
                    { onChange(draft.copy(otherReason = it)) }, stringResource(R.string.inspection_count_other_note),
                    Modifier.fillMaxWidth(), singleLine = false, minLines = 2, maxLines = 5)
                SentriButton(stringResource(R.string.inspection_count_reset), onReset,
                    Modifier.fillMaxWidth(), primary = false)
            }
            SentriButton(stringResource(R.string.inspection_count_save), {}, Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

@Composable
private fun CountPreviewStepper(label: String, value: Int, minimum: Int, maximum: Int,
    onChange: (Int) -> Unit, draftDelta: Int = 0, primary: Boolean = false) {
    SentriPanel(Modifier.fillMaxWidth()) {
        SentriStepper(label, value, minimum, maximum, onChange, Modifier.padding(12.dp),
            face = if (primary) SentriStepperFace.COUNT else SentriStepperFace.ROW,
            draftDelta = draftDelta,
            lowerBoundReason = if (primary) stringResource(R.string.inspection_count_floor, minimum) else null)
    }
}

@Composable
private fun countReasonLabel(reason: PenCountReason, delta: Int): String = stringResource(when (reason) {
    PenCountReason.DIED_UNRECORDED -> R.string.inspection_count_died
    PenCountReason.BORN_UNRECORDED -> R.string.inspection_count_born
    PenCountReason.MOVED_UNRECORDED -> if (delta > 0) R.string.inspection_count_moved_in else R.string.inspection_count_moved_out
    PenCountReason.MISCOUNT -> R.string.inspection_count_miscount
    PenCountReason.OTHER -> R.string.inspection_count_other
})

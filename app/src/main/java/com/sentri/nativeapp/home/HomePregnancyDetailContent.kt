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
import com.sentri.nativeapp.production.PregnancyPig
import com.sentri.nativeapp.production.PregnancyStatus

/** Read-only task snapshot; the backend only supplies the latest check, not named check rounds. */
@Composable
fun HomePregnancyDetailContent(
    task: HomeScopedTask,
    allowedUnitIds: Set<String>,
    state: HomePregnancyDetailState?,
    onRefresh: () -> Unit,
) {
    val colors = SentriTheme.colors
    Text(stringResource(R.string.connected_batch, task.task.batch.serial),
        fontFamily = SentriType.sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = colors.ink)
    Text(stringResource(R.string.connected_pregnancy_last_result_note),
        Modifier.padding(top = 8.dp, bottom = 14.dp), fontFamily = SentriType.sans,
        fontSize = 12.sp, color = colors.muted)
    when {
        state == null || state.taskId != task.task.id || state.loading ->
            Text(stringResource(R.string.connected_pregnancy_loading), color = colors.muted)
        state.failed -> {
            Text(stringResource(R.string.connected_pregnancy_error), color = colors.muted)
            SentriButton(stringResource(R.string.connected_pregnancy_retry), onRefresh,
                Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        state.result?.value == null -> Text(stringResource(R.string.connected_pregnancy_removed), color = colors.muted)
        state.result.value.id != task.task.id || state.result.value.batchId != task.task.batch.id ->
            Text(stringResource(R.string.connected_pregnancy_batch_changed), color = colors.muted)
        else -> {
            val result = state.result
            if (result.source == DataSource.CACHE)
                Text(stringResource(R.string.connected_offline_snapshot),
                    Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                    fontSize = 12.sp, color = colors.muted)
            val pigs = result.value.pigs.filter { !it.disabled && it.location.unitId in allowedUnitIds }
            Text(stringResource(R.string.connected_pregnancy_record_count, pigs.size),
                Modifier.padding(bottom = 10.dp), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (pigs.isEmpty()) Text(stringResource(R.string.connected_pregnancy_no_current_pigs), color = colors.muted)
            else {
                var shown by rememberSaveable(task.task.id, allowedUnitIds) { mutableIntStateOf(30) }
                SentriPanel(Modifier.fillMaxWidth()) {
                    Column {
                        pigs.take(shown).forEach { pig ->
                            SentriRow(pigLabel(pig), pig.earNotch?.let {
                                stringResource(R.string.connected_pregnancy_ear_notch, it)
                            }, null, null, trailing = resultLabel(pig), trailingTag = true)
                        }
                    }
                }
                if (shown < pigs.size) SentriButton(
                    stringResource(R.string.connected_pregnancy_more, pigs.size - shown),
                    { shown += 30 }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}

@Composable private fun pigLabel(pig: PregnancyPig): String =
    pig.earTag?.takeIf(String::isNotBlank) ?: pig.earNotch?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.connected_pregnancy_untagged)

@Composable private fun resultLabel(pig: PregnancyPig): String = when (pig.result?.status) {
    PregnancyStatus.PREGNANT -> stringResource(R.string.connected_pregnancy_pregnant)
    PregnancyStatus.NOT_PREGNANT -> stringResource(R.string.connected_pregnancy_not_pregnant)
    PregnancyStatus.NOT_SURE -> stringResource(R.string.connected_pregnancy_recheck)
    null -> stringResource(R.string.connected_pregnancy_unrecorded)
}

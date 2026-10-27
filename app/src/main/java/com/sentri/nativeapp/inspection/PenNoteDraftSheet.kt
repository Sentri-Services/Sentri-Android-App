package com.sentri.nativeapp.inspection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriField
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType

/** A draft editor until the server can identify and version the pen's current note. */
@Composable
internal fun PenNoteDraftSheet(penCode: String, state: PenNoteEditor, onChange: (String) -> Unit,
    onSave: () -> Unit, onClear: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = SentriTheme.colors
    SentriSheet(stringResource(R.string.inspection_pen_note_title), stringResource(R.string.home_back),
        stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(penCode, fontFamily = SentriType.mono, fontSize = 13.sp, color = colors.muted)
            Text(stringResource(R.string.inspection_pen_note_draft_help), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (!state.loaded) {
                Text(stringResource(when {
                    state.loading -> R.string.inspection_pen_note_loading
                    state.saving -> R.string.inspection_pen_note_saving
                    state.saveFailed -> R.string.inspection_pen_note_save_failed
                    else -> R.string.inspection_pen_note_load_failed
                }), fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted)
                if (!state.loading && !state.saving) {
                    SentriButton(stringResource(R.string.inspection_retry), onRetry, Modifier.fillMaxWidth())
                    if (state.loadFailed) SentriButton(stringResource(R.string.inspection_pen_note_clear),
                        onClear, Modifier.fillMaxWidth(), primary = false)
                }
                return@Column
            }
            SentriField(state.text, onChange, stringResource(R.string.inspection_pen_note_field),
                Modifier.fillMaxWidth(), singleLine = false, minLines = 5, maxLines = 12,
                enabled = !state.saving, reason = stringResource(R.string.inspection_pen_note_saving),
                hint = stringResource(when {
                    state.saveFailed -> R.string.inspection_pen_note_save_failed
                    state.unchanged -> R.string.inspection_pen_note_saved
                    else -> R.string.inspection_pen_note_unsaved
                }))
            SentriButton(stringResource(R.string.inspection_pen_note_save_draft), onSave,
                Modifier.fillMaxWidth(), primary = false,
                enabled = state.text.isNotBlank() && !state.saving && !state.unchanged)
            if (state.text.isNotEmpty() || state.saved != null)
                SentriButton(stringResource(R.string.inspection_pen_note_clear), onClear,
                    Modifier.fillMaxWidth(), primary = false, enabled = !state.saving)
            SentriButton(stringResource(R.string.inspection_pen_note_submit), {},
                Modifier.fillMaxWidth(), enabled = false)
        }
    }
}

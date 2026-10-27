package com.sentri.nativeapp.inspection

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriField
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceFaultDraftSheet(penCode: String, openFaults: List<DeviceInspection>,
    draft: DeviceFaultDraft, onDraftChange: (DeviceFaultDraft) -> Unit,
    onExistingFault: (String) -> Unit, onDismiss: () -> Unit,
    loading: Boolean = false, loadFailed: Boolean = false, saving: Boolean = false,
    saved: Boolean = false, saveFailed: Boolean = false,
    onSave: (() -> Unit)? = null, onRetry: () -> Unit = {}) {
    val colors = SentriTheme.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        onDraftChange(draft.copy(photoUris = (draft.photoUris + selected).distinct().take(12)))
    }
    SentriSheet(stringResource(R.string.inspection_fault_report_title), stringResource(R.string.home_back),
        stringResource(R.string.home_close), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.inspection_fault_report_pen, penCode), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (loading || loadFailed) {
                Text(stringResource(if (loading) R.string.inspection_fault_draft_loading
                    else R.string.inspection_fault_draft_load_failed), color = colors.muted,
                    fontFamily = SentriType.sans, fontSize = 13.sp)
                if (loadFailed) SentriButton(stringResource(R.string.inspection_retry), onRetry,
                    Modifier.fillMaxWidth(), primary = false)
                return@Column
            }
            Text(stringResource(R.string.inspection_fault_report_device), fontFamily = SentriType.sans,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            listOf("Feed", "Water", "Other").forEach { type ->
                val existing = openFaults.firstOrNull { it.deviceType == type && type != "Other" }
                SentriRow(deviceTypeLabel(type), existing?.let {
                    stringResource(R.string.inspection_fault_report_existing)
                }, null, onClick = {
                    if (saving) return@SentriRow
                    if (existing != null) onExistingFault(existing.id)
                    else onDraftChange(draft.copy(type = type))
                }, selected = draft.type == type)
            }
            if (draft.type == "Other") SentriField(draft.otherName,
                { onDraftChange(draft.copy(otherName = it)) }, stringResource(R.string.inspection_fault_report_other_name),
                Modifier.fillMaxWidth(), readOnly = saving)
            SentriField(draft.description, { onDraftChange(draft.copy(description = it)) },
                stringResource(R.string.inspection_fault_report_description), Modifier.fillMaxWidth(),
                singleLine = false, minLines = 3, maxLines = 6, readOnly = saving)
            Text(stringResource(R.string.inspection_fault_report_photos, draft.photoUris.size),
                fontFamily = SentriType.sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = colors.ink)
            if (draft.photoUris.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.photoUris.forEach { uri ->
                    Column {
                        FaultPhotoPreview(uri)
                        SentriIconButton("close", stringResource(R.string.inspection_fault_report_remove_photo),
                            { if (!saving) onDraftChange(draft.copy(photoUris = draft.photoUris - uri)) })
                    }
                }
            }
            if (draft.photoUris.size < 12) SentriButton(stringResource(R.string.inspection_fault_report_choose_photos),
                { launcher.launch("image/*") }, Modifier.fillMaxWidth(), primary = false, enabled = !saving)
            Text(stringResource(R.string.inspection_fault_report_contract_pending), fontFamily = SentriType.sans,
                fontSize = 12.sp, color = colors.muted)
            if (saving || saved || saveFailed) Text(stringResource(when {
                saving -> R.string.inspection_fault_draft_saving
                saveFailed -> R.string.inspection_fault_draft_save_failed
                else -> R.string.inspection_fault_draft_saved
            }), fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            if (onSave != null && (!draft.empty || saveFailed))
                SentriButton(stringResource(R.string.inspection_fault_draft_save), onSave,
                    Modifier.fillMaxWidth(), primary = false, enabled = !saving && !saved)
            if (!draft.empty) SentriButton(stringResource(R.string.inspection_fault_report_clear_draft),
                { onDraftChange(DeviceFaultDraft()) }, Modifier.fillMaxWidth(), primary = false, enabled = !saving)
            SentriButton(stringResource(R.string.inspection_fault_report_submit), {}, Modifier.fillMaxWidth(),
                enabled = false)
            if (!draft.valid) Text(stringResource(R.string.inspection_fault_report_required),
                fontFamily = SentriType.sans, fontSize = 12.sp, color = colors.muted)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun deviceTypeLabel(type: String): String = stringResource(when (type) {
    "Feed" -> R.string.inspection_pen_fault_feed
    "Water" -> R.string.inspection_pen_fault_water
    else -> R.string.inspection_pen_fault_other
})

@Composable private fun FaultPhotoPreview(uri: Uri) {
    val context = LocalContext.current
    val preview by produceState<Pair<Boolean, ImageBitmap?>>(false to null, uri) {
        value = false to null
        val thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 160).coerceAtLeast(1)
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?.asImageBitmap()
            }.getOrNull()
        }
        value = true to thumbnail
    }
    if (preview.second != null) Image(preview.second!!, contentDescription = stringResource(R.string.inspection_fault_report_photo),
        modifier = Modifier.size(72.dp))
    else Text(stringResource(if (preview.first) R.string.inspection_fault_photo_unavailable
        else R.string.inspection_fault_report_photo), Modifier.size(72.dp).padding(8.dp),
        fontFamily = SentriType.sans, fontSize = 11.sp, color = SentriTheme.colors.muted)
}

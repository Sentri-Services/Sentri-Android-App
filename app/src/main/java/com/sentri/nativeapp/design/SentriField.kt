package com.sentri.nativeapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R

/** Android review of Field (candidate, Sentri-UI f66affd). The host owns validation and persistence. */
@Composable
fun SentriField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, enabled: Boolean = true,
    readOnly: Boolean = false, optional: Boolean = false, unit: String? = null,
    hint: String? = null, error: String? = null, reason: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle? = null) {
    val colors = SentriTheme.colors
    val unavailable = reason?.takeIf { !enabled }
        ?: if (!enabled) stringResource(R.string.sentri_field_disabled) else null
    val message = error ?: unavailable ?: hint
    val fieldLabel = if (unit.isNullOrBlank()) label else "$label · $unit"
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().semantics {
        error?.let { error(it) }
        unavailable?.let { stateDescription = it }
    }, enabled = enabled, readOnly = readOnly, singleLine = singleLine,
        minLines = minLines, maxLines = maxLines,
        textStyle = textStyle ?: TextStyle(fontFamily = SentriType.sans, fontSize = 16.sp,
            lineHeight = 24.sp, color = if (enabled) colors.ink else colors.muted),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        cursorBrush = SolidColor(colors.ink), decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(fieldLabel, Modifier.weight(1f), fontFamily = SentriType.sans,
                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    if (optional) Text(stringResource(R.string.sentri_field_optional),
                        fontFamily = SentriType.sans, fontSize = 13.sp, lineHeight = 20.sp, color = colors.muted)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = SentriDimens.tapMin)
                    .background(if (enabled) colors.paper else colors.disabledSurface,
                        RoundedCornerShape(SentriDimens.controlRadius))
                    .border(1.dp, if (error != null) colors.red else colors.controlBorder,
                        RoundedCornerShape(SentriDimens.controlRadius))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) { input() }
                if (message != null) Text(message, fontFamily = SentriType.sans,
                    fontSize = 13.sp, lineHeight = 19.sp, color = if (error != null) colors.red else colors.muted)
            }
        })
}

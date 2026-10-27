package com.sentri.nativeapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R

enum class SentriStepperFace { ROW, COUNT }

/** Two currently consumed candidate faces. Bounds are enforced here and again by the host's domain model. */
@Composable
fun SentriStepper(label: String, value: Int, minimum: Int, maximum: Int, onChange: (Int) -> Unit,
    modifier: Modifier = Modifier, face: SentriStepperFace = SentriStepperFace.ROW,
    draftDelta: Int? = null, enabled: Boolean = true, loading: Boolean = false,
    reason: String? = null, error: String? = null, status: String? = null,
    lowerBoundReason: String? = null) {
    require(minimum <= value && value <= maximum)
    val colors = SentriTheme.colors
    val decrease = stringResource(R.string.inspection_count_decrease, label)
    val increase = stringResource(R.string.inspection_count_increase, label)
    val lower = lowerBoundReason ?: stringResource(R.string.sentri_stepper_minimum, minimum)
    val upper = stringResource(R.string.sentri_stepper_maximum, maximum)
    val disabledReason = reason ?: stringResource(R.string.sentri_field_disabled)
    var refused by remember(value, minimum, maximum, enabled, loading) { mutableStateOf<String?>(null) }
    val draft = draftDelta?.takeIf { it != 0 }?.let {
        stringResource(R.string.sentri_stepper_unsaved, if (it > 0) "+$it" else it.toString())
    }
    val message = error ?: if (loading) stringResource(R.string.sentri_stepper_loading)
        else if (!enabled) disabledReason else refused ?: status ?: draft
    fun request(delta: Int): Boolean {
        if (!enabled || loading) return false
        if (delta < 0 && value <= minimum) { refused = lower; return false }
        if (delta > 0 && value >= maximum) { refused = upper; return false }
        onChange(value + delta)
        return true
    }
    @Composable fun figure(modifier: Modifier) {
        Box(modifier.heightIn(min = SentriDimens.tapMin).onKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionUp -> { request(1); true }
                Key.DirectionDown -> { request(-1); true }
                else -> false
            }
        }.focusable().semantics(mergeDescendants = true) {
            contentDescription = label
            message?.let { stateDescription = it }
            customActions = listOf(CustomAccessibilityAction(decrease) { request(-1) },
                CustomAccessibilityAction(increase) { request(1) })
        }, contentAlignment = Alignment.Center) {
            Text(value.toString(), fontFamily = SentriType.mono,
                fontSize = if (face == SentriStepperFace.COUNT) 42.sp else 24.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
    }
    @Composable fun keys(modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepperKey("minus", decrease, !enabled || loading || value == minimum,
                face == SentriStepperFace.COUNT) { request(-1) }
            figure(Modifier.weight(1f))
            StepperKey("plus", increase, !enabled || loading || value == maximum,
                face == SentriStepperFace.COUNT) { request(1) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (face == SentriStepperFace.COUNT) {
            Text(label, Modifier.align(Alignment.CenterHorizontally), fontFamily = SentriType.sans,
                fontSize = 16.sp, lineHeight = 22.sp, color = colors.muted)
            keys(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().heightIn(min = 19.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                if (message != null) Text(message, Modifier.align(Alignment.Center), fontFamily = SentriType.sans,
                    fontSize = 13.sp, lineHeight = 19.sp, color = if (error != null) colors.red else colors.muted)
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(label, fontFamily = SentriType.sans, fontSize = 14.sp, lineHeight = 20.sp, color = colors.ink)
                if (message != null) Text(message, fontFamily = SentriType.sans, fontSize = 13.sp,
                    lineHeight = 19.sp, color = if (error != null) colors.red else colors.muted)
            }
            keys(Modifier.width(156.dp))
        }
    }
}

@Composable
private fun StepperKey(icon: String, label: String, atBound: Boolean, large: Boolean, onClick: () -> Unit) {
    val colors = SentriTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius = if (large) 14.dp else SentriDimens.controlRadius
    Box(Modifier.size(if (large) 54.dp else SentriDimens.tapMin)
        .clip(RoundedCornerShape(radius))
        .background(if (atBound) colors.disabledSurface else if (pressed) colors.well else colors.paper)
        .drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(colors.controlBorder, Offset(stroke / 2, stroke / 2),
                Size(size.width - stroke, size.height - stroke), CornerRadius(radius.toPx()),
                style = Stroke(stroke, pathEffect = if (atBound)
                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
        }.semantics { if (atBound) disabled() }
        .focusable().clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        SentriIcon(icon, label, Modifier.size(if (large) 24.dp else 20.dp),
            if (atBound) colors.inkSecondary else colors.ink)
    }
}

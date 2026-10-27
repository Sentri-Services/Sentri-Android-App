package com.sentri.nativeapp.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Animal face of Row. Names are neutral: callers must not imply Care without real finding data. */
@Composable
fun SentriAnimalRow(code: String, context: String, conditionNames: List<String>,
    conditionDescription: String, onOpen: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean = false, selectionLabel: String? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null, highlightQuery: String? = null,
    arriving: Boolean = false) {
    val colors = SentriTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val background by animateColorAsState(when {
        arriving -> colors.greenWash
        selected || pressed -> colors.well
        else -> Color.Transparent
    }, label = "animal row background")
    Row(modifier.fillMaxWidth().background(background),
        verticalAlignment = Alignment.CenterVertically) {
        if (onSelectedChange != null) Checkbox(selected, onSelectedChange,
            Modifier.size(48.dp).semantics {
                selectionLabel?.let { contentDescription = it }
            }, colors = CheckboxDefaults.colors(checkedColor = colors.ink,
                checkmarkColor = colors.paper, uncheckedColor = colors.muted))
        Row(Modifier.weight(1f).defaultMinSize(minHeight = 72.dp)
            .clickable(interactionSource = interactions, indication = null, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(animalSearchMatch(code, highlightQuery, colors.ink, colors.ink),
                    fontFamily = SentriType.mono, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(animalSearchMatch(context, highlightQuery, colors.muted, colors.ink),
                    fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnimalConditionNames(conditionNames, conditionDescription, Modifier.weight(1f))
            SentriIcon("chevron", null, Modifier.size(17.dp), colors.muted)
        }
    }
}

private fun animalSearchMatch(value: String, query: String?, base: Color, matchColor: Color): AnnotatedString =
    buildAnnotatedString {
        val start = query?.takeIf(String::isNotBlank)?.let { value.indexOf(it, ignoreCase = true) } ?: -1
        if (start < 0 || query == null) withStyle(SpanStyle(color = base)) { append(value) }
        else {
            withStyle(SpanStyle(color = base)) { append(value.take(start)) }
            withStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.Bold)) {
                append(value.substring(start, start + query.length))
            }
            withStyle(SpanStyle(color = base)) { append(value.drop(start + query.length)) }
        }
    }

@Composable
private fun AnimalConditionNames(names: List<String>, description: String, modifier: Modifier) {
    val colors = SentriTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = SentriType.sans, fontSize = 13.sp, color = colors.ink)
    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val available = with(density) { maxWidth.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val widths = names.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        val allFit = widths.sum() + gap * (names.size - 1).coerceAtLeast(0) <= available
        val count = if (allFit || names.size <= 1) names.size else {
            (names.size - 1 downTo 1).firstOrNull { shown ->
                val remaining = measurer.measure("+${names.size - shown}", style,
                    softWrap = false, maxLines = 1).size.width
                widths.take(shown).sum() + gap * shown + remaining <= available
            } ?: 1
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            if (names.isEmpty()) Text("—", style = style.copy(color = colors.muted))
            names.take(count).forEach { name ->
                Text(name, modifier = (if (count == 1) Modifier.weight(1f, fill = false) else Modifier)
                    .clearAndSetSemantics {},
                    style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (count < names.size) Text("+${names.size - count}", style = style)
        }
    }
}

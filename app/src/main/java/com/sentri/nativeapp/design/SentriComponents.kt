package com.sentri.nativeapp.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SentriDimens {
    val gutter = 18.dp
    val narrowGutter = 12.dp
    val panelPadding = 16.dp
    val panelRadius = 18.dp
    val insetRadius = 14.dp
    val controlRadius = 12.dp
    val segmentRadius = 10.dp
    val sheetRadius = 28.dp
    val tapMin = 48.dp
    val sectionGap = 24.dp
}

@Composable
fun SentriIcon(name: String, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = SentriTheme.colors.ink) {
    val resource = SentriIcons.resolve(name) ?: return
    Icon(painterResource(resource), contentDescription = contentDescription, modifier = modifier, tint = tint)
}

@Composable
fun SentriPanel(modifier: Modifier = Modifier, background: Color = SentriTheme.colors.paper, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = background,
        shape = RoundedCornerShape(SentriDimens.panelRadius),
        border = BorderStroke(1.dp, SentriTheme.colors.line),
    ) { content() }
}

@Composable
fun SentriSectionHeading(title: String, modifier: Modifier = Modifier, meta: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = SentriType.sans, fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink, modifier = Modifier.weight(1f))
        meta?.let { Text(it, fontFamily = SentriType.mono, fontSize = 10.sp, color = SentriTheme.colors.muted) }
    }
}

@Composable
fun SentriButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true,
    enabled: Boolean = true, leadingIcon: String? = null) {
    val colors = SentriTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = SentriDimens.tapMin),
        enabled = enabled,
        shape = RoundedCornerShape(SentriDimens.controlRadius),
        border = if (primary) null else BorderStroke(1.dp, colors.controlBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) colors.ink else colors.paper,
            contentColor = if (primary) colors.paper else colors.ink,
        ),
    ) {
        leadingIcon?.let {
            SentriIcon(it, null, Modifier.size(17.dp), if (primary) colors.paper else colors.ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontFamily = SentriType.sans, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SentriIconButton(name: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    bordered: Boolean = true, enabled: Boolean = true) {
    val colors = SentriTheme.colors
    Box(
        modifier = modifier.size(SentriDimens.tapMin)
            .clip(RoundedCornerShape(SentriDimens.controlRadius))
            .then(if (bordered) Modifier.border(1.dp, if (enabled) colors.controlBorder else colors.disabledBorder,
                RoundedCornerShape(SentriDimens.controlRadius)) else Modifier)
            .then(if (!enabled) Modifier.focusable() else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { SentriIcon(name, label, Modifier.size(20.dp), if (enabled) colors.ink else colors.disabledInk) }
}

@Composable
fun SentriBanner(
    title: String,
    detail: String,
    icon: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Notice,
) {
    val colors = SentriTheme.colors
    val background = if (tone == BannerTone.Notice) colors.pendingSurface else colors.greenWash
    val border = if (tone == BannerTone.Notice) colors.pendingBorder else colors.controlBorder
    val accent = if (tone == BannerTone.Notice) colors.amber else colors.green
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp)
            .clip(RoundedCornerShape(SentriDimens.insetRadius))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(SentriDimens.insetRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SentriIcon(icon, null, Modifier.size(21.dp), tint = accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontFamily = SentriType.sans, fontSize = 12.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(detail, fontFamily = SentriType.sans, fontSize = 10.sp, lineHeight = 15.sp, color = colors.muted)
        }
        if (onClick != null) SentriIcon("chevron", null, Modifier.size(16.dp), tint = colors.muted)
    }
}

enum class BannerTone { Notice, Success }

@Composable
fun SentriRow(
    title: String,
    detail: String?,
    icon: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    current: Boolean = false,
    trailing: String? = null,
    menuCurrent: Boolean = false,
    trailingTag: Boolean = false,
) {
    val colors = SentriTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowFill = when {
        pressed -> Color(0xFFEAF0E2)
        selected || current -> colors.greenWash
        else -> Color.Transparent
    }
    Box(modifier.fillMaxWidth().background(rowFill)
        .then(if (onClick != null) Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick) else Modifier)) {
        if (current) Box(Modifier.matchParentSize().drawBehind {
            drawRect(Color(0xFF526847), size = Size(3.dp.toPx(), size.height))
        })
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(SentriDimens.segmentRadius))
                    .background(colors.rowIconBackground), contentAlignment = Alignment.Center) {
                    SentriIcon(it, null, Modifier.size(20.dp), colors.rowIconInk)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontFamily = SentriType.sans, fontSize = 14.sp,
                    fontWeight = if (menuCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.ink)
                detail?.let { Text(it, fontFamily = SentriType.sans, fontSize = 11.sp,
                    color = colors.muted) }
            }
            trailing?.let {
                if (trailingTag) Box(Modifier.clip(RoundedCornerShape(8.dp)).background(colors.well)
                    .padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(it, fontFamily = SentriType.sans, fontSize = 10.sp, color = colors.muted)
                } else Text(it, fontFamily = SentriType.sans, fontSize = 11.sp, color = colors.muted)
            }
            if (selected || menuCurrent || onClick != null) SentriIcon(if (selected || menuCurrent) "check" else "chevron", null,
                Modifier.size(17.dp), if (selected) colors.green else if (menuCurrent) colors.ink else colors.muted)
        }
    }
}

@Composable
fun SentriSheet(title: String, backLabel: String, closeLabel: String, onDismiss: () -> Unit,
    onClose: () -> Unit = onDismiss, content: @Composable () -> Unit) {
    val colors = SentriTheme.colors
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - 56.dp
    val visible = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    var closingEntireSheet by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            visible.targetState = false
        }
    }
    val close: () -> Unit = {
        if (!closing) {
            closingEntireSheet = true
            closing = true
            visible.targetState = false
        }
    }
    LaunchedEffect(Unit) { visible.targetState = true }
    LaunchedEffect(closing, visible.isIdle, visible.currentState) {
        if (closing && visible.isIdle && !visible.currentState) {
            if (closingEntireSheet) onClose() else onDismiss()
        }
    }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible.targetState) 1f else 0f,
        animationSpec = tween(if (visible.targetState) 140 else 180), label = "Sheet scrim")
    val arrive = remember { CubicBezierEasing(.22f, 1f, .36f, 1f) }
    val depart = remember { CubicBezierEasing(.42f, 0f, 1f, 1f) }
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false)) {
        val view = LocalView.current
        val density = LocalDensity.current
        DisposableEffect(view, density) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                val hadDimFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                val previousDim = window.attributes.dimAmount
                if (hadDimFlag) window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val hadBlurFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
                val previousRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.attributes.blurBehindRadius else 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val params = window.attributes
                    params.blurBehindRadius = with(density) { 4.dp.roundToPx() }
                    window.attributes = params
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
                onDispose {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val restored = window.attributes
                        restored.blurBehindRadius = previousRadius
                        window.attributes = restored
                        if (!hadBlurFlag) window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    }
                    if (hadDimFlag) {
                        window.setDimAmount(previousDim)
                        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    }
                }
            } else onDispose { }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()
                .background(Color(0x4937402C).copy(alpha = 0x49 / 255f * scrimAlpha))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = closeLabel, onClick = close))
            AnimatedVisibility(visibleState = visible, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(240, easing = arrive)) { it },
                exit = slideOutVertically(tween(180, easing = depart)) { it }) {
                Surface(Modifier.fillMaxWidth(), color = colors.paper,
                    shape = RoundedCornerShape(topStart = SentriDimens.sheetRadius, topEnd = SentriDimens.sheetRadius),
                    shadowElevation = 14.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
                        Column(Modifier.pointerInput(closing) {
                            val threshold = 72.dp.toPx()
                            var distance = 0f
                            detectVerticalDragGestures(
                                onDragStart = { distance = 0f },
                                onVerticalDrag = { change, amount ->
                                    distance = (distance + amount).coerceAtLeast(0f)
                                    change.consume()
                                },
                                onDragEnd = { if (distance >= threshold) close() },
                            )
                        }) {
                            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.padding(top = 8.dp).width(42.dp).height(4.dp)
                                    .background(colors.controlBorder, RoundedCornerShape(2.dp)))
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 21.dp, end = 16.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(title, Modifier.weight(1f), fontFamily = SentriType.sans, fontSize = 20.sp,
                                    lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                                SentriIconButton("close", closeLabel, close, bordered = false)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(start = 21.dp, end = 21.dp, top = 16.dp, bottom = 18.dp)) {
                            content()
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                        Box(Modifier.fillMaxWidth().padding(start = 21.dp, end = 21.dp, top = 14.dp, bottom = 38.dp)) {
                            SentriButton(backLabel, dismiss, Modifier.fillMaxWidth(), leadingIcon = "back")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SentriProgress(value: Int, total: Int, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val fraction = if (total > 0) (value.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(4.dp))
        .background(SentriTheme.colors.well)) {
        Box(Modifier.fillMaxWidth(fraction).height(height).background(Color(0xFF66835B)))
    }
}

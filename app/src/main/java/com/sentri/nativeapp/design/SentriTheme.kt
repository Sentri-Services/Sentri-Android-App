package com.sentri.nativeapp.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R

/** Approved light foundations from Sentri-UI tokens.json at 410b1dc. */
data class SentriPalette(
    val background: Color = Color(0xFFF6F7F1),
    val paper: Color = Color.White,
    val surface: Color = Color(0xFFF8FAF5),
    val inset: Color = Color(0xFFF6F8F1),
    val well: Color = Color(0xFFEEF1E7),
    val ink: Color = Color(0xFF20291F),
    val muted: Color = Color(0xFF657060),
    val line: Color = Color(0xFFDFE4D8),
    val rule: Color = Color(0xFFE8EDDF),
    val controlBorder: Color = Color(0xFFBDC7B6),
    val disabledSurface: Color = Color(0xFFF1F2ED),
    val inkSecondary: Color = Color(0xFF465143),
    val disabledInk: Color = Color(0xFF92998D),
    val disabledBorder: Color = Color(0xFFD9DDD3),
    val green: Color = Color(0xFF276640),
    val greenWash: Color = Color(0xFFEAF2E5),
    val amber: Color = Color(0xFF896017),
    val amberWash: Color = Color(0xFFFBF6E8),
    val pendingSurface: Color = Color(0xFFFCF7EC),
    val pendingBorder: Color = Color(0xFFE6DCC5),
    val red: Color = Color(0xFFA13A2D),
    val redWash: Color = Color(0xFFFFF1EE),
    val progress: Color = Color(0xFF466888),
    val focus: Color = Color(0xFF577FA6),
    val rowIconBackground: Color = Color(0xFFEEF2E7),
    val rowIconInk: Color = Color(0xFF53674A),
)

object SentriType {
    val sans = FontFamily(
        Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
        Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
        Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    )
    val mono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))
}

private val LocalSentriPalette = staticCompositionLocalOf { SentriPalette() }

object SentriTheme {
    val colors: SentriPalette
        @Composable @ReadOnlyComposable get() = LocalSentriPalette.current
}

@Composable
fun SentriAppTheme(content: @Composable () -> Unit) {
    val colors = SentriPalette()
    val material = lightColorScheme(
        primary = colors.green,
        onPrimary = colors.paper,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.paper,
        onSurface = colors.ink,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.muted,
        outline = colors.controlBorder,
        error = colors.red,
    )
    val type = Typography(
        bodyLarge = TextStyle(fontFamily = SentriType.sans, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = SentriType.sans, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = SentriType.sans, fontSize = 12.sp, lineHeight = 18.sp),
        titleLarge = TextStyle(fontFamily = SentriType.sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = SentriType.sans, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontFamily = SentriType.sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalSentriPalette provides colors) {
        MaterialTheme(colorScheme = material, typography = type, content = content)
    }
}

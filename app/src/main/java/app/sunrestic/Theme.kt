package app.sunrestic

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Every text style uses the system font (FontFamily.Default = whatever font the phone uses). Nothing in the
 * app sets its own font. Sizes are medium: body 15sp, secondary text 13sp, titles 16-18sp.
 */
private fun systemTypography(): Typography {
    val base = Typography()
    fun TextStyle.sys(size: Int, line: Int, weight: FontWeight? = null) = copy(
        fontFamily = FontFamily.Default,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight ?: fontWeight,
    )
    return Typography(
        displayLarge = base.displayLarge.sys(48, 56),
        displayMedium = base.displayMedium.sys(40, 48),
        displaySmall = base.displaySmall.sys(32, 40),
        headlineLarge = base.headlineLarge.sys(26, 32, FontWeight.Bold),
        headlineMedium = base.headlineMedium.sys(22, 28, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.sys(18, 24, FontWeight.Medium),
        titleLarge = base.titleLarge.sys(18, 24, FontWeight.SemiBold),
        titleMedium = base.titleMedium.sys(15, 20, FontWeight.Medium),
        titleSmall = base.titleSmall.sys(13, 18, FontWeight.Medium),
        bodyLarge = base.bodyLarge.sys(15, 21),
        bodyMedium = base.bodyMedium.sys(13, 18),
        bodySmall = base.bodySmall.sys(12, 16),
        labelLarge = base.labelLarge.sys(13, 18, FontWeight.Medium),
        labelMedium = base.labelMedium.sys(12, 16, FontWeight.Medium),
        labelSmall = base.labelSmall.sys(11, 14, FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * One identity in both modes: calm tonal surfaces and a single teal accent.
 * Cards are flat (a slightly different tone from the page, no borders or shadows).
 */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F6F62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EBE5),
    onPrimaryContainer = Color(0xFF0C3A33),
    secondary = Color(0xFF566B66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE8E5),
    onSecondaryContainer = Color(0xFF1B2D29),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C7),
    onTertiaryContainer = Color(0xFF4A2B00),
    background = Color(0xFFF3F5F4),
    onBackground = Color(0xFF161C1B),
    surface = Color(0xFFF3F5F4),
    onSurface = Color(0xFF161C1B),
    surfaceVariant = Color(0xFFE1E7E5),
    onSurfaceVariant = Color(0xFF55615E),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF8C1D18),
    outline = Color(0xFF8A9693),
    outlineVariant = Color(0xFFD6DDDB),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE1E7E5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEBEFEE),
    surfaceContainerHigh = Color(0xFFE6ECEA),
    surfaceContainerHighest = Color(0xFFDCE3E1),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF7FD6C4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF1B4F46),
    onPrimaryContainer = Color(0xFFBFF0E5),
    secondary = Color(0xFFA9C2BC),
    onSecondary = Color(0xFF1C3430),
    secondaryContainer = Color(0xFF2A3F3B),
    onSecondaryContainer = Color(0xFFD0E8E2),
    tertiary = Color(0xFFF0BC6A),
    onTertiary = Color(0xFF3F2A00),
    tertiaryContainer = Color(0xFF4A3410),
    onTertiaryContainer = Color(0xFFFFDDA8),
    background = Color(0xFF0E1312),
    onBackground = Color(0xFFE1E8E6),
    surface = Color(0xFF0E1312),
    onSurface = Color(0xFFE1E8E6),
    surfaceVariant = Color(0xFF1F2927),
    onSurfaceVariant = Color(0xFFA7B3B0),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE1E8E6),
    inverseOnSurface = Color(0xFF1B2120),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C2421),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF77837F),
    outlineVariant = Color(0xFF2F3A38),
    surfaceBright = Color(0xFF212B29),
    surfaceDim = Color(0xFF0E1312),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF161D1C),
    surfaceContainer = Color(0xFF1A2220),
    surfaceContainerHigh = Color(0xFF212B29),
    surfaceContainerHighest = Color(0xFF2B3634),
)

/** Colours that Material has no slot for: Swift's pastel category pills, status green, app tags. */
@Immutable
data class ExtraColors(
    val dark: Boolean,
    val good: Color,
    val tagBlue: Color,
    val tagBrown: Color,
    val tagRed: Color,
    val onTag: Color,
)

val LocalExtra = staticCompositionLocalOf {
    ExtraColors(false, Color(0xFF2E8B57), Color(0xFF1F6F62), Color(0xFF9A5B00), Color(0xFFB3261E), Color.White)
}

/**
 * In dark mode Material's "low" surface is only a hair lighter than the page, so cards (which use it) nearly vanish,
 * and even more on pure black. Moving every tone up one step makes cards clearly lighter than the page, and what sits
 * on a card (buttons, text fields, chips) lighter again.
 */
private fun ColorScheme.liftedForDark(): ColorScheme = copy(
    surfaceContainerLow = surfaceContainerHigh,
    surfaceContainerHigh = surfaceContainerHighest,
    surfaceContainerHighest = androidx.compose.ui.graphics.lerp(surfaceContainerHighest, onSurface, 0.10f),
)

/** Cards two steps lighter than the page, for the normal (not pure black) dark theme. */
private fun ColorScheme.liftedStrongForDark(): ColorScheme = copy(
    surfaceContainerLow = surfaceContainerHighest,
    surfaceContainerHigh = androidx.compose.ui.graphics.lerp(surfaceContainerHighest, onSurface, 0.10f),
    surfaceContainerHighest = androidx.compose.ui.graphics.lerp(surfaceContainerHighest, onSurface, 0.18f),
)

/**
 * Follows the system: Material You colours from the wallpaper accent on Android 12+, the built-in teal palette
 * below that. Follows system dark mode. [amoled] makes dark pure black.
 */
@Composable
fun AppTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx).let {
            // Cards (surfaceContainerLow) stand out from the page by being the lighter tone.
            it.copy(background = it.surfaceContainer, surface = it.surfaceContainer, surfaceContainerLow = it.surfaceBright)
        }
        dark -> DarkScheme
        else -> LightScheme
    }
    val scheme = when {
        // Pure black page: a one-step lift is enough for cards to stand out.
        dark && amoled -> base.liftedForDark().copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
        // Tinted dark page: the page stays dark grey, so cards are lifted two steps to still stand out from it.
        dark -> base.liftedStrongForDark()
        else -> base
    }
    val extra = ExtraColors(
        dark = dark,
        good = if (dark) Color(0xFF7FD6A0) else Color(0xFF2E8B57),
        tagBlue = scheme.primary,
        tagBrown = scheme.tertiary,
        tagRed = scheme.error,
        onTag = scheme.onPrimary,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val typography = systemTypography()
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = AppShapes,
        ) {
            // Plain Text() without a style also gets the system font.
            CompositionLocalProvider(LocalTextStyle provides typography.bodyLarge, content = content)
        }
    }
}

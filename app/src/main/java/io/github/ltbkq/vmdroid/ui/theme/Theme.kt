/*
 * Vmdroid Compose theme.
 *
 * Default identity: fixed dark/light palette derived from VmdroidTokens, with
 * a lime-green accent (#4ade80). Material You wallpaper-derived dynamic color
 * is OFF by default — users opt in via Settings → Appearance → Dynamic color,
 * which flows in via the dynamicColor parameter.
 */
package io.github.ltbkq.vmdroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val VmdroidDark = darkColorScheme(
    primary             = VmdroidAccent,
    onPrimary           = VmdroidAccentInk,
    primaryContainer    = VmdroidDarkSurface2,
    onPrimaryContainer  = VmdroidDarkText,

    secondary           = VmdroidAccent,
    onSecondary         = VmdroidAccentInk,
    secondaryContainer  = VmdroidDarkSurface2,
    onSecondaryContainer= VmdroidDarkText,

    tertiary            = VmdroidAmber,
    onTertiary          = VmdroidAccentInk,

    background          = VmdroidDarkBg,
    onBackground        = VmdroidDarkText,
    surface             = VmdroidDarkSurface,
    onSurface           = VmdroidDarkText,
    surfaceVariant      = VmdroidDarkSurface2,
    onSurfaceVariant    = VmdroidDarkTextMute,
    surfaceContainerHighest = VmdroidDarkSurface2,

    outline             = VmdroidDarkBorder,
    outlineVariant      = VmdroidDarkBorder,

    error               = VmdroidRed,
    onError             = VmdroidDarkText,
    errorContainer      = VmdroidDarkSurface2,
    onErrorContainer    = VmdroidRed,
)

private val VmdroidLight = lightColorScheme(
    primary             = VmdroidAccent,
    onPrimary           = VmdroidAccentInk,
    primaryContainer    = VmdroidLightSurface2,
    onPrimaryContainer  = VmdroidLightText,

    secondary           = VmdroidAccent,
    onSecondary         = VmdroidAccentInk,
    secondaryContainer  = VmdroidLightSurface2,
    onSecondaryContainer= VmdroidLightText,

    tertiary            = VmdroidAmber,
    onTertiary          = VmdroidLightText,

    background          = VmdroidLightBg,
    onBackground        = VmdroidLightText,
    surface             = VmdroidLightSurface,
    onSurface           = VmdroidLightText,
    surfaceVariant      = VmdroidLightSurface2,
    onSurfaceVariant    = VmdroidLightTextMute,
    surfaceContainerHighest = VmdroidLightSurface2,

    outline             = VmdroidLightBorder,
    outlineVariant      = VmdroidLightBorder,

    error               = VmdroidRed,
    onError             = VmdroidLightText,
    errorContainer      = VmdroidLightSurface2,
    onErrorContainer    = VmdroidRed,
)

@Composable
fun VmdroidTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        effectiveDark -> VmdroidDark
        else          -> VmdroidLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = buildVmdroidTypography(),
        content     = content,
    )
}

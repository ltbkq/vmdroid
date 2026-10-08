package io.github.ltbkq.vmdroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A container that constrains its content to a maximum width on large screens
 * to follow Material 3 adaptive layout best practices. Compact widths
 * fall through to the caller's modifier unchanged.
 */
@Composable
fun AdaptiveContainer(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    maxWidth: Int = 600,
    content: @Composable () -> Unit
) {
    val widthClass = windowSizeClass.widthSizeClass
    val constrainWidth = widthClass == WindowWidthSizeClass.Expanded ||
        widthClass == WindowWidthSizeClass.Medium

    val sizing = if (constrainWidth) {
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = maxWidth.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Box(modifier = modifier.then(sizing)) {
        content()
    }
}

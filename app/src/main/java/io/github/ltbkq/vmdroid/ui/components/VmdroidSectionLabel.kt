package io.github.ltbkq.vmdroid.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.ltbkq.vmdroid.ui.theme.VmdroidTokens

/**
 * Tiny uppercase tracked label used as a section heading throughout the app.
 * Pairs naturally with VmdroidListRow groups.
 */
@Composable
fun VmdroidSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = VmdroidTokens.ui(),
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(top = VmdroidTokens.Spacing.LG, bottom = VmdroidTokens.Spacing.XS),
    )
}

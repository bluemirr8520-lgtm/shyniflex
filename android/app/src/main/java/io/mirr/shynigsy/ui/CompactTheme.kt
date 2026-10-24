package io.mirr.shynigsy.ui

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape

/** Menus stay clear at rest; touch, keyboard focus and selection supply the highlight. */
@Composable
internal fun CompactMenuAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = enabled && (selected || focused || pressed)
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RectangleShape, interactionSource = interaction, contentPadding = contentPadding,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (active) colors.primary.copy(alpha = if (focused || pressed) .22f else .12f) else Color.Transparent,
            contentColor = if (selected || focused) colors.primary else colors.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurface.copy(alpha = .38f)), content = content)
}

/** Compact browsing chrome; subtitle sizing and the user's system font scale remain independent. */
internal val CompactTypography = Typography(
    displayLarge = TextStyle(fontSize = 36.sp, lineHeight = 42.sp),
    displayMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp),
    displaySmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp),
    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

internal val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

package dev.openimager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Raspberry crimson and leaf green, the colours of the hardware this app writes cards for. */
private val Crimson = Color(0xFFC51A4A)
private val CrimsonDark = Color(0xFF8E0F33)
private val Leaf = Color(0xFF6CC04A)
private val LeafDark = Color(0xFF3F7A28)

private val LightColors = lightColorScheme(
    primary = Crimson,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E0),
    onPrimaryContainer = Color(0xFF3F0016),
    secondary = LeafDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F3C6),
    onSecondaryContainer = Color(0xFF11290A),
    surface = Color(0xFFFDF7F8),
    surfaceVariant = Color(0xFFF3E4E8),
    background = Color(0xFFFDF7F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C1),
    onPrimary = Color(0xFF66052A),
    primaryContainer = CrimsonDark,
    onPrimaryContainer = Color(0xFFFFD9E0),
    secondary = Leaf,
    onSecondary = Color(0xFF11290A),
    secondaryContainer = LeafDark,
    onSecondaryContainer = Color(0xFFD7F3C6),
    surface = Color(0xFF1A1113),
    surfaceVariant = Color(0xFF3B2C30),
    background = Color(0xFF1A1113),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
)

@Composable
fun OpenImagerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography.copy(
            headlineSmall = AppTypography.headlineSmall,
            titleMedium = AppTypography.titleMedium,
        ),
        content = content,
    )
}

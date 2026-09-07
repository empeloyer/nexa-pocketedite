package com.btcsignal.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    background = BgBase,
    surface = BgSurface,
    surfaceVariant = BgSurfaceElevated,
    primary = AccentBlue,
    secondary = GreenSignal,
    error = RedSignal,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = BgBase,
    outline = BorderSubtle
)

val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = TextPrimary),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 13.sp, color = TextSecondary),
    // Previously undefined, which left it on Material3's unstyled default color -
    // rendered as unreadable black text on this app's dark glass cards (e.g. the AI
    // Model section). Give it an explicit color like every other style here.
    bodySmall = TextStyle(fontSize = 11.sp, color = TextSecondary),
    labelSmall = TextStyle(fontSize = 11.sp, color = TextSecondary)
)

@Composable
fun BtcSignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}

package com.btcsignal.app.ui.theme

import androidx.compose.ui.graphics.Color

// Base surfaces — deep space navy, tuned for translucent "glass" panels to sit on top of.
val BgBase = Color(0xFF07080F)
val BgSurface = Color(0xFF141925)
val BgSurfaceElevated = Color(0xFF1B2230)

// Glass panel fills/strokes: low-alpha whites over the dark gradient background give the
// frosted-glass look without needing a real-time blur (kept simple/cheap, minSdk 26).
val GlassFill = Color(0x14FFFFFF)
val GlassFillElevated = Color(0x1FFFFFFF)
val GlassStroke = Color(0x33FFFFFF)
val GlassHighlight = Color(0x40FFFFFF)

// Background gradient stops (used behind the whole app, glass cards float on this).
val GradientTop = Color(0xFF141B34)
val GradientMid = Color(0xFF0B0E1C)
val GradientBottom = Color(0xFF07080F)

val GreenSignal = Color(0xFF2FE6A6)
val RedSignal = Color(0xFFFF5C7A)
val AmberWarning = Color(0xFFFFC24B)
val TextPrimary = Color(0xFFF3F5FA)
val TextSecondary = Color(0xFF9AA3B8)
val BorderSubtle = Color(0x26FFFFFF)
val AccentBlue = Color(0xFF4FC3F7)
val AccentPurple = Color(0xFFA78BFA)

// Colorful per-tab accents for the bottom navigation bar.
val TabLiveColor = Color(0xFF4FC3F7)
val TabStrategiesColor = Color(0xFFB388FF)
val TabBacktestColor = Color(0xFFFFC24B)
val TabHistoryColor = Color(0xFF64D8CB)
val TabPerformanceColor = Color(0xFFFF7597)
val TabSettingsColor = Color(0xFF9AA3B8)
val TabAiColor = Color(0xFFFFD54F)

// Readable accent for the AI panel's explanatory copy (was rendering as unreadable
// black text on the dark glass card — MaterialTheme.typography.bodySmall has no color
// override, see AppTypography in Theme.kt — so it's now given an explicit warm-yellow
// color here instead of relying on the default).
val AiInfoYellow = Color(0xFFFFD54F)

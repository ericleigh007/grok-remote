package com.xai.grokremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Desktop-ish Grok dark palette
val Bg = Color(0xFF0B1020)
val Panel = Color(0xFF121A2F)
val Panel2 = Color(0xFF1A2440)
val TextPrimary = Color(0xFFE8EEFC)
val Muted = Color(0xFF8B9BB8)
val Accent = Color(0xFF6EA8FF)
val Accent2 = Color(0xFF3D7EFF)
val UserBubble = Color(0xFF1E3A5F)
val AgentBubble = Color(0xFF162033)
val ThoughtBg = Color(0xFF1A2238)
val ToolBg = Color(0xFF2A2140)
val Danger = Color(0xFFFF6B7A)
val Ok = Color(0xFF3DD68C)
val Warn = Color(0xFFF0C14B)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    background = Bg,
    surface = Panel,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Danger,
    surfaceVariant = Panel2,
    onSurfaceVariant = Muted,
)

@Composable
fun GrokRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}

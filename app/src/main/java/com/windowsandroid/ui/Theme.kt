package com.windowsandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Midnight sapphire, after WindowsiOS's Surface Laptop palette, with Windows blue as the accent. */
object Palette {
    val Night = Color(0xFF0A0F1C)
    val Deck = Color(0xFF111A2E)
    val Panel = Color(0xFF16213A)
    val Line = Color(0xFF26324D)
    val Accent = Color(0xFF3B8EEA)
    val WindowsBlue = Color(0xFF0078D4)
    val Text = Color(0xFFE7ECF6)
    val Muted = Color(0xFF93A0BA)
    val Good = Color(0xFF3CCB7F)
    val Warn = Color(0xFFF2B33D)
    val Bad = Color(0xFFF0645A)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.WindowsBlue,
    background = Palette.Night,
    onBackground = Palette.Text,
    surface = Palette.Deck,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Panel,
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Line,
    error = Palette.Bad,
)

@Composable
fun WindowsAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

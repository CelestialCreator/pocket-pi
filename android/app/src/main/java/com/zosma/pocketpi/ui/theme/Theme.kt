package com.zosma.pocketpi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE6E1E5),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF111111),
    onPrimary = Color(0xFF000000),
    onBackground = Color(0xFFEFEFEF),
    onSurface = Color(0xFFEFEFEF),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111),
)

@Composable
fun PocketPiTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}

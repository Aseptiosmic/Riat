package com.riat.lyane.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LyaneViolet = Color(0xFF8B7CFF)
private val LyaneDeep = Color(0xFF5B4BD6)
private val LyaneCyan = Color(0xFF4DD6C1)

private val LightScheme = lightColorScheme(
    primary = LyaneDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF190A5E),
    secondary = Color(0xFF00696B),
    secondaryContainer = Color(0xFFB6F0EF),
    tertiary = Color(0xFF7A4FC9),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE5E1EC)
)

private val DarkScheme = darkColorScheme(
    primary = LyaneViolet,
    onPrimary = Color(0xFF160A5E),
    primaryContainer = Color(0xFF3A2E8C),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = LyaneCyan,
    secondaryContainer = Color(0xFF004F51),
    tertiary = Color(0xFFCBB6FF),
    background = Color(0xFF131019),
    surface = Color(0xFF131019),
    surfaceVariant = Color(0xFF2B2733)
)

@Composable
fun LyaneTheme(
    themeSetting: String = "system",
    content: @Composable () -> Unit
) {
    val dark = when (themeSetting) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

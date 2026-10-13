package com.tarslauncher.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color.Transparent, // Şeffaf arka plan
    surface = Color.Transparent, // Şeffaf surface
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun TarslauncherTheme(
    darkTheme: Boolean = true, // Herzaman dark mode
    dynamicColor: Boolean = false, // Dynamic color devre dışı
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Herzaman dark scheme kullan

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
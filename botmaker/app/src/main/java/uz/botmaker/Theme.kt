package uz.botmaker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun BotMakerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF6CC4F0), onPrimary = Color(0xFF00344D))
    } else {
        lightColorScheme(primary = Color(0xFF1E88C7), onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

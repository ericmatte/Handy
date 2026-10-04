package computer.handy.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HandyPink = Color(0xFFF7A8CB)
private val HandyPinkDeep = Color(0xFFB0306F)
private val HandyInk = Color(0xFF2A1A22)

private val LightColors = lightColorScheme(
    primary = HandyPinkDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = HandyInk,
    secondary = Color(0xFF74565F),
    surface = Color(0xFFFFFBFC),
    onSurface = HandyInk,
)

private val DarkColors = darkColorScheme(
    primary = HandyPink,
    onPrimary = HandyInk,
    primaryContainer = Color(0xFF8E2459),
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = Color(0xFFE2BDC6),
)

@Composable
fun HandyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

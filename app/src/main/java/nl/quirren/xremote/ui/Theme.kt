package nl.quirren.xremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0B0B0C)
val Panel = Color(0xFF151517)
val Tile = Color(0xFF1F2023)
val TileHi = Color(0xFF2A2B2F)
val Accent = Color(0xFFE8A33D)
val TextHi = Color(0xFFEDEDED)
val TextDim = Color(0xFF8C8C93)
val Danger = Color(0xFFE5534B)

@Composable
fun XRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Bg, background = Bg, surface = Panel,
            onSurface = TextHi, onBackground = TextHi, surfaceVariant = Tile,
        ),
        content = content,
    )
}

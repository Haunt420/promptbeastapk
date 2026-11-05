package app.promptbeast

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Ember & Ink: the desktop palette (theme/tokens.py) mapped onto Material3 roles. */
private val Dark = darkColorScheme(
    primary = Color(0xFFE39A62), onPrimary = Color(0xFF1B1009),
    primaryContainer = Color(0xFF4A2E18), onPrimaryContainer = Color(0xFFF0B184),
    secondary = Color(0xFF7FC3CB), onSecondary = Color(0xFF0F1114),
    secondaryContainer = Color(0xFF1C4249), onSecondaryContainer = Color(0xFFECE6DC),
    tertiary = Color(0xFFD8B458), onTertiary = Color(0xFF1B1405),
    background = Color(0xFF0F1114), onBackground = Color(0xFFECE6DC),
    surface = Color(0xFF191C21), onSurface = Color(0xFFECE6DC),
    surfaceVariant = Color(0xFF242830), onSurfaceVariant = Color(0xFFAAA49A),
    surfaceContainerLowest = Color(0xFF0F1114), surfaceContainerLow = Color(0xFF14171B),
    surfaceContainer = Color(0xFF1A1D22), surfaceContainerHigh = Color(0xFF242830),
    surfaceContainerHighest = Color(0xFF2C313A),
    outline = Color(0xFF7A766F), outlineVariant = Color(0xFF353A43),
    error = Color(0xFFE5675F), onError = Color(0xFF1B0A08),
)

private val Light = lightColorScheme(
    primary = Color(0xFFB4582A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DCCB), onPrimaryContainer = Color(0xFF5A2A10),
    secondary = Color(0xFF2B6A73), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE5E8), onSecondaryContainer = Color(0xFF12353B),
    tertiary = Color(0xFF8F6A1A), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF1ECE4), onBackground = Color(0xFF221D17),
    surface = Color(0xFFFAF7F2), onSurface = Color(0xFF221D17),
    surfaceVariant = Color(0xFFE8E1D5), onSurfaceVariant = Color(0xFF5E564B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFFAF7F2), surfaceContainerHigh = Color(0xFFEFE9DE),
    surfaceContainerHighest = Color(0xFFE8E1D5),
    outline = Color(0xFF8A8174), outlineVariant = Color(0xFFD6CEC0),
    error = Color(0xFFB73225), onError = Color(0xFFFFFFFF),
)

@Composable
fun PromptBeastTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}

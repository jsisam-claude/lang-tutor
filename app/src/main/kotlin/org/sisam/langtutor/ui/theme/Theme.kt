package org.sisam.langtutor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Coral = Color(0xFFFF6B57)
private val Teal = Color(0xFF19B8A6)
private val Sun = Color(0xFFFFC145)
private val Cream = Color(0xFFFFF6EC)
private val Ink = Color(0xFF2B2140)

private val KidColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = Sun,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

// Kids' app: a single bright light theme by design (no dark variant for now).
@Composable
fun LangTutorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KidColorScheme,
        content = content,
    )
}

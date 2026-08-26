package org.sisam.langtutor.ui.sticker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * What a child can win.
 *
 * Ids are stable strings and never reused, because a collection is stored by
 * id: renaming one would silently rewrite what a child already owns. Adding to
 * the end of this list is always safe.
 */
data class Sticker(val id: String, val glyph: String, val color: Color)

val STICKER_BOOK: List<Sticker> = listOf(
    Sticker("star", "⭐", Color(0xFFFFC145)),
    Sticker("rocket", "🚀", Color(0xFF7C6BEA)),
    Sticker("rainbow", "🌈", Color(0xFF3E8ED0)),
    Sticker("cat", "🐱", Color(0xFFE0679B)),
    Sticker("flower", "🌸", Color(0xFFFF9BC2)),
    Sticker("dino", "🦖", Color(0xFF2E9E5B)),
    Sticker("icecream", "🍦", Color(0xFF19B8A6)),
    Sticker("ball", "⚽", Color(0xFFFF6B57)),
)

fun stickerById(id: String): Sticker? = STICKER_BOOK.firstOrNull { it.id == id }

/**
 * One sticker, drawn to look like a sticker: a die-cut white border, a colour
 * fill, and a gloss highlight across the top-left. That white ring is doing
 * most of the work — it is what separates "a picture on a circle" from "a
 * thing you could peel off and stick somewhere".
 *
 * The face itself is an emoji rather than hand-rolled vector art. It costs no
 * bytes, renders as full colour at any size on the target devices, and is
 * already how the unit cards are marked — eight bespoke illustrations would
 * look worse and take a week.
 */
@Composable
fun StickerFace(sticker: Sticker, size: Dp, modifier: Modifier = Modifier) {
    // Cancel the system font scale out of the glyph. The circle around it is
    // decoration that A11y has already shrunk, so a glyph that kept growing
    // with the font setting would burst straight out of its own die-cut edge.
    val fontScale = LocalDensity.current.fontScale
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            // Die-cut border.
            drawCircle(Color.White, radius = r, center = c)
            drawCircle(sticker.color, radius = r * 0.86f, center = c)
            drawCircle(sticker.color.copy(alpha = 0.45f), radius = r * 0.70f, center = c)
            // Gloss: a soft ellipse across the upper left, the cue every glossy
            // printed sticker has and nothing flat does.
            drawOval(
                color = Color.White.copy(alpha = 0.30f),
                topLeft = Offset(c.x - r * 0.66f, c.y - r * 0.74f),
                size = Size(r * 1.05f, r * 0.62f),
            )
        }
        Text(
            text = sticker.glyph,
            style = TextStyle(
                fontSize = (size.value * 0.46f / fontScale).sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

package org.sisam.langtutor.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Scenery, drawn rather than shipped.
 *
 * Same doctrine as [TukiParrot]: every ornament in this file is Canvas
 * geometry, so it costs zero APK bytes, is sharp at any density, and needs no
 * artwork pipeline or third-party licence. Nothing here is interactive or
 * carries meaning — screens are readable with all of it removed, which is what
 * lets [A11y] shrink or drop it when the user has asked for bigger text.
 */

/** A five-pointed star centred on [c] with outer radius [r]. Shared geometry:
 *  the backdrop twinkles with it and the reward burst flies it. */
internal fun starPath(c: Offset, r: Float, points: Int = 5, innerRatio: Float = 0.42f): Path {
    val path = Path()
    val step = PI / points
    // Start at -90 degrees so the star points up rather than sideways.
    var angle = -PI / 2
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) r else r * innerRatio
        val x = c.x + (radius * cos(angle)).toFloat()
        val y = c.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        angle += step
    }
    path.close()
    return path
}

/** A six-armed flake centred on [c]. Drawn as strokes by the caller's colour. */
internal fun DrawScope.drawFlake(c: Offset, r: Float, color: Color, alpha: Float = 1f) {
    for (i in 0 until 3) {
        val a = i * PI / 3
        val dx = (r * cos(a)).toFloat()
        val dy = (r * sin(a)).toFloat()
        drawLine(
            color = color,
            start = Offset(c.x - dx, c.y - dy),
            end = Offset(c.x + dx, c.y + dy),
            strokeWidth = r * 0.22f,
            alpha = alpha,
        )
    }
    drawCircle(color = color, radius = r * 0.2f, center = c, alpha = alpha)
}

/** A coin: gold disc, darker rim, a star struck into the face. */
internal fun DrawScope.drawCoin(c: Offset, r: Float, alpha: Float = 1f, spin: Float = 1f) {
    // `spin` squashes the disc horizontally so a tumbling coin reads as 3D
    // without any of the cost of actually rotating one.
    val w = (r * spin).coerceAtLeast(r * 0.12f)
    drawOval(
        color = Color(0xFFD9931F),
        topLeft = Offset(c.x - w, c.y - r),
        size = Size(w * 2, r * 2),
        alpha = alpha,
    )
    drawOval(
        color = Color(0xFFFFD54A),
        topLeft = Offset(c.x - w * 0.78f, c.y - r * 0.78f),
        size = Size(w * 1.56f, r * 1.56f),
        alpha = alpha,
    )
    if (spin > 0.55f) {
        drawPath(starPath(c, r * 0.5f), Color(0xFFD9931F), alpha = alpha)
    }
}

/**
 * The sky Tuki lives in: a soft vertical wash with a low sun and three drifting
 * cloud banks. Sized by its [Modifier] — the caller decides how much of the
 * screen this is allowed to occupy.
 *
 * [animated] can be switched off for a still background (it is, on the busy
 * Home hero, where a permanently moving backdrop competes with the buttons).
 */
@Composable
fun SkyBackdrop(
    modifier: Modifier = Modifier,
    top: Color = Color(0xFFBFE9FF),
    bottom: Color = Color(0xFFFFF6EC),
    animated: Boolean = true,
) {
    val loop = rememberInfiniteTransition(label = "sky")
    val drift by loop.animateFloat(
        initialValue = 0f,
        targetValue = if (animated) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "sky-drift",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(brush = Brush.verticalGradient(listOf(top, bottom)), size = size)

        // Sun: two discs, the outer one a halo.
        drawCircle(Color(0xFFFFD98A), radius = h * 0.26f, center = Offset(w * 0.84f, h * 0.16f), alpha = 0.55f)
        drawCircle(Color(0xFFFFC145), radius = h * 0.15f, center = Offset(w * 0.84f, h * 0.16f))

        // Clouds: overlapping discs, each bank drifting at its own speed so
        // the layers separate and the sky reads as deep.
        cloud(Offset((w * (0.12f + drift * 0.9f)) % (w * 1.4f) - w * 0.2f, h * 0.22f), h * 0.11f, 0.85f)
        cloud(Offset((w * (0.62f + drift * 0.55f)) % (w * 1.4f) - w * 0.2f, h * 0.42f), h * 0.08f, 0.65f)
        cloud(Offset((w * (0.38f + drift * 1.3f)) % (w * 1.4f) - w * 0.2f, h * 0.10f), h * 0.06f, 0.5f)
    }
}

private fun DrawScope.cloud(at: Offset, r: Float, alpha: Float) {
    val white = Color.White
    drawCircle(white, radius = r, center = at, alpha = alpha)
    drawCircle(white, radius = r * 0.78f, center = Offset(at.x + r * 0.9f, at.y + r * 0.12f), alpha = alpha)
    drawCircle(white, radius = r * 0.62f, center = Offset(at.x - r * 0.85f, at.y + r * 0.18f), alpha = alpha)
    drawOval(
        color = white,
        topLeft = Offset(at.x - r * 1.5f, at.y),
        size = Size(r * 3f, r * 0.9f),
        alpha = alpha,
    )
}

/**
 * Slow twinkling motes — small stars that fade in and out on their own phase.
 * Purely atmospheric; used behind the splash so a four-second wait has
 * something alive in it without implying progress that isn't happening.
 */
@Composable
fun TwinkleField(modifier: Modifier = Modifier, count: Int = 14, color: Color = Color.White) {
    val loop = rememberInfiniteTransition(label = "twinkle")
    val t by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing), RepeatMode.Restart),
        label = "twinkle-t",
    )

    Canvas(modifier = modifier) {
        // Deterministic pseudo-random placement: a hash of the index, so the
        // field is stable across recompositions without allocating a Random.
        for (i in 0 until count) {
            val hx = ((i * 2654435761u.toLong()) % 1000L) / 1000f
            val hy = ((i * 40503L * 31L) % 997L) / 997f
            val phase = ((i * 7919L) % 1000L) / 1000f
            val alpha = 0.25f + 0.75f * (0.5f + 0.5f * sin((t + phase) * 2 * PI).toFloat())
            val r = size.minDimension * (0.006f + 0.008f * hy)
            drawPath(
                starPath(Offset(size.width * hx, size.height * hy), r * 2.4f),
                color,
                alpha = alpha * 0.8f,
            )
        }
    }
}

/**
 * The branch Tuki perches on — a simple bough with two leaves, which is what
 * turns a floating bird into a scene. Drawn under the mascot by the caller.
 */
@Composable
fun Perch(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bark = Color(0xFF8D6034)
        drawBough(w, h, bark)
        val leaf = Color(0xFF2E9E5B)
        drawOval(leaf, Offset(w * 0.10f, h * 0.05f), Size(w * 0.22f, h * 0.45f))
        drawOval(Color(0xFF1F7A44), Offset(w * 0.72f, h * 0.0f), Size(w * 0.20f, h * 0.40f))
    }
}

/** The bough itself: a flattened oval spanning the full width. */
private fun DrawScope.drawBough(w: Float, h: Float, color: Color) {
    drawOval(color, Offset(0f, h * 0.42f), Size(w, h * 0.34f))
}

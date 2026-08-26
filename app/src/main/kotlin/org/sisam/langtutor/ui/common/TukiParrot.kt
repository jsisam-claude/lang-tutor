package org.sisam.langtutor.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * Tuki, drawn rather than shipped: a vector parrot on a Canvas, so it costs no
 * APK bytes, scales to any density, and needs no artwork pipeline.
 *
 * When [speaking] the beak opens and closes and the head bobs with it, which is
 * the whole point — a pre-reader who cannot follow the transcript still gets an
 * unambiguous "it is talking to me now" signal. The motion is driven by ONE
 * looping phase; [speaking] only scales its amplitude, so starting and stopping
 * eases in and out instead of snapping, and an idle Tuki is perfectly still
 * rather than quietly animating off-screen.
 */
@Composable
fun TukiParrot(
    speaking: Boolean,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val loop = rememberInfiniteTransition(label = "tuki-loop")
    // ~3.5 beak cycles a second: fast enough to read as speech, slow enough
    // not to look like a glitch.
    val phase by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 280), RepeatMode.Reverse),
        label = "tuki-phase",
    )
    val amplitude by animateFloatAsState(
        targetValue = if (speaking) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "tuki-amplitude",
    )

    Canvas(modifier = modifier.size(size)) {
        drawTuki(beakOpen = phase * amplitude, headBob = (phase - 0.5f) * amplitude)
    }
}

private fun DrawScope.drawTuki(beakOpen: Float, headBob: Float) {
    val w = this.size.width
    val h = this.size.height
    val body = Color(0xFF2E9E5B) // green
    val bodyDark = Color(0xFF1F7A44)
    val head = Color(0xFFE4483D) // red crown, classic macaw palette
    val beak = Color(0xFFF7B733) // amber
    val beakDark = Color(0xFFD9931F)

    // Tail: two feathers sweeping down-left, drawn first so the body overlaps.
    val tail = Path().apply {
        moveTo(w * 0.34f, h * 0.62f)
        lineTo(w * 0.05f, h * 0.95f)
        lineTo(w * 0.22f, h * 0.90f)
        lineTo(w * 0.30f, h * 0.98f)
        lineTo(w * 0.44f, h * 0.74f)
        close()
    }
    drawPath(tail, bodyDark)

    // Body
    drawOval(
        color = body,
        topLeft = Offset(w * 0.26f, h * 0.34f),
        size = Size(w * 0.48f, h * 0.52f),
    )
    // Wing
    drawOval(
        color = bodyDark,
        topLeft = Offset(w * 0.34f, h * 0.46f),
        size = Size(w * 0.26f, h * 0.30f),
    )

    // Head + beak rotate together, pivoting at the neck, so the bob reads as
    // one motion instead of parts sliding against each other.
    val pivot = Offset(w * 0.55f, h * 0.42f)
    rotate(degrees = headBob * 7f, pivot = pivot) {
        drawCircle(color = head, radius = w * 0.19f, center = Offset(w * 0.58f, h * 0.28f))
        // Eye
        drawCircle(color = Color.White, radius = w * 0.055f, center = Offset(w * 0.65f, h * 0.24f))
        drawCircle(color = Color(0xFF23201E), radius = w * 0.028f, center = Offset(w * 0.665f, h * 0.245f))

        // Upper mandible: fixed to the head, hooked like a parrot's.
        val upper = Path().apply {
            moveTo(w * 0.74f, h * 0.22f)
            lineTo(w * 0.95f, h * 0.30f)
            lineTo(w * 0.78f, h * 0.36f)
            close()
        }
        drawPath(upper, beak)

        // Lower mandible: hinged at the corner of the mouth and swung down by
        // beakOpen, which is what actually animates.
        rotate(degrees = beakOpen * 20f, pivot = Offset(w * 0.75f, h * 0.34f)) {
            val lower = Path().apply {
                moveTo(w * 0.75f, h * 0.34f)
                lineTo(w * 0.90f, h * 0.34f)
                lineTo(w * 0.76f, h * 0.42f)
                close()
            }
            drawPath(lower, beakDark)
        }
    }

    // Feet
    drawCircle(color = beakDark, radius = w * 0.035f, center = Offset(w * 0.44f, h * 0.88f))
    drawCircle(color = beakDark, radius = w * 0.035f, center = Offset(w * 0.56f, h * 0.88f))
}

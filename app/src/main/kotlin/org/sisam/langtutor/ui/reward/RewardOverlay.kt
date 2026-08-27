package org.sisam.langtutor.ui.reward

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import org.sisam.langtutor.ui.common.drawCoin
import org.sisam.langtutor.ui.common.drawFlake
import org.sisam.langtutor.ui.common.starInto
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The celebration layer: coins, stars and flakes flying over whatever screen
 * the learner is on.
 *
 * Sits above the whole navigation graph, so a burst that starts as a lesson
 * ends still finishes while the next screen is drawing. It never takes input —
 * a bare Canvas consumes no pointer events — because a reward that swallows
 * the tap the child was about to make is a punishment.
 *
 * Every particle's trajectory is derived from a hash of (burst id, index), so
 * a burst looks random and is identical across recompositions without a
 * `Random` instance or a per-particle state object. The star and coin geometry
 * is written into ONE scratch path that is reused for every particle of every
 * frame — a fresh Path per star would be ~20 allocations a frame, on a device
 * that is also decoding a language model.
 */
@Composable
fun RewardOverlay(bus: RewardBus, modifier: Modifier = Modifier) {
    val bursts by bus.active.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        for (burst in bursts) {
            key(burst.id) {
                Burst(burst = burst, onFinished = { bus.finished(burst.id) })
            }
        }
    }
}

@Composable
private fun Burst(burst: RewardBurst, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scratch = remember { Path() }
    LaunchedEffect(burst.id) {
        // Linear on purpose: gravity is applied inside the trajectory, so any
        // easing here would fight it and the arcs would stop reading as arcs.
        progress.animateTo(1f, tween(durationMillis = FLIGHT_MS, easing = LinearEasing))
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawBurst(burst, progress.value, scratch)
    }
}

private fun DrawScope.drawBurst(burst: RewardBurst, t: Float, scratch: Path) {
    // Launched from low-centre, the way a fountain reads. Higher up and the
    // particles fall off the top before the eye catches them.
    val originX = size.width * 0.5f
    val originY = size.height * 0.80f
    val unit = size.minDimension

    for (i in 0 until burst.count) {
        val spread = rand(burst.id, i, 1)
        val power = rand(burst.id, i, 2)
        val sizeJitter = rand(burst.id, i, 3)
        val phase = rand(burst.id, i, 4)
        val lateral = rand(burst.id, i, 5)

        val kind = particleKind(burst.kind, i)
        // Flakes are the gentle cue: they drift rather than launch, so they get
        // a fraction of the impulse and a sideways sway instead of an arc.
        val gentle = kind == RewardKind.FLAKE

        // Fan upward: -0.15pi to -0.85pi keeps everything above horizontal.
        val angle = -PI * (0.15 + 0.70 * spread)
        val speed = size.height * (if (gentle) 0.35f else 0.85f) * (0.7f + 0.6f * power)

        val x = originX +
            (cos(angle) * speed * t).toFloat() +
            if (gentle) (unit * 0.12f * sin((t * 3.0 + phase * 6.28).toDouble())).toFloat() else 0f
        val y = originY +
            (sin(angle) * speed * t).toFloat() +
            GRAVITY * size.height * t * t

        // Fade in fast so nothing pops, hold, then fade with the fall.
        val alpha = when {
            t < 0.06f -> t / 0.06f
            t > 0.70f -> ((1f - t) / 0.30f).coerceIn(0f, 1f)
            else -> 1f
        }
        if (alpha <= 0f) continue

        val r = unit * (0.022f + 0.016f * sizeJitter)
        val at = Offset(x, y)

        when (kind) {
            RewardKind.COIN -> {
                // Tumbling: the disc squashes horizontally through the spin,
                // which reads as 3D for a fraction of the cost of being 3D.
                val spin = kotlin.math.abs(cos((t * 9.0 + phase * 6.28))).toFloat()
                drawCoin(at, r, alpha = alpha, spin = 0.12f + 0.88f * spin, scratch = scratch)
            }

            RewardKind.STAR -> {
                rotate(degrees = (phase * 360f) + t * 220f * (if (lateral > 0.5f) 1f else -1f), pivot = at) {
                    drawPath(starInto(scratch, at, r * 1.25f), STAR_GOLD, alpha = alpha)
                    drawPath(starInto(scratch, at, r * 0.62f), STAR_CORE, alpha = alpha * 0.9f)
                }
            }

            RewardKind.FLAKE -> drawFlake(at, r * 0.9f, FLAKE_WHITE, alpha = alpha * 0.85f)

            // MIX never reaches a particle: particleKind() has already resolved
            // it to one of the three above.
            RewardKind.MIX -> Unit
        }
    }
}

private fun particleKind(kind: RewardKind, index: Int): RewardKind =
    if (kind != RewardKind.MIX) {
        kind
    } else {
        // Coins carry the weight, stars the sparkle, flakes the softness.
        // Uneven on purpose — an even third each looks like a test pattern.
        when (index % 5) {
            0, 1 -> RewardKind.COIN
            2, 3 -> RewardKind.STAR
            else -> RewardKind.FLAKE
        }
    }

/**
 * Deterministic 0..1 from (burst, particle, channel). A cheap integer hash
 * rather than a `Random`: every frame recomputes every particle, and a
 * stateful generator would give a different answer each time and make the
 * whole burst flicker.
 */
private fun rand(burstId: Long, index: Int, channel: Int): Float {
    var h = burstId * 0x9E3779B97F4A7C15uL.toLong()
    h = h xor (index * 0xBF58476D1CE4E5B9uL.toLong())
    h = h xor (channel * 0x94D049BB133111EBuL.toLong())
    h = h xor (h ushr 31)
    h *= 0x7FB5D329728EA185L
    h = h xor (h ushr 27)
    return ((h ushr 33).toInt() % 10_000) / 10_000f
}

private const val FLIGHT_MS = 1_700
/** Fraction of the screen height pulled back down over the full flight. */
private const val GRAVITY = 1.15f

private val STAR_GOLD = Color(0xFFFFC145)
private val STAR_CORE = Color(0xFFFFF3C4)
private val FLAKE_WHITE = Color(0xFFEAF6FF)

package org.sisam.langtutor.ui.reward

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What flies across the screen. */
enum class RewardKind {
    /** Gold, tumbling, weighty — for earning something. */
    COIN,

    /** Bright and sharp — for doing something WELL, not merely doing it. */
    STAR,

    /** Slow, soft, drifting — gentle praise that does not shout. */
    FLAKE,

    /** All three at once. Reserved for the moments that actually matter. */
    MIX,
}

/**
 * One celebration in flight. [id] is what keys its animation, so two identical
 * bursts a second apart are still two bursts rather than a recomposition of
 * one.
 */
data class RewardBurst(
    val id: Long,
    val kind: RewardKind,
    val count: Int,
)

/**
 * The reward channel: anything in the app can ask for a celebration, and one
 * overlay draws them all.
 *
 * Deliberately app-wide rather than per-screen. Rewards fire from a turn
 * completing, a lesson finishing, a sticker being picked — and the child
 * should see the same coins wherever they are, including if they navigate
 * mid-flight.
 *
 * Bursts stay in [active] until the overlay reports them finished, so nothing
 * is dropped by a screen that happened not to be composed when it fired.
 */
class RewardBus {

    private val nextId = AtomicLong(1L)
    private val _active = MutableStateFlow<List<RewardBurst>>(emptyList())
    val active: StateFlow<List<RewardBurst>> = _active

    /** @return false when the burst was dropped because too many are already
     *  in flight — the caller uses it to keep the sound in step with the
     *  picture instead of chiming over nothing. */
    fun celebrate(kind: RewardKind, count: Int = defaultCount(kind)): Boolean {
        // A hard ceiling on concurrent bursts: a child who taps fast should get
        // a livelier screen, not an unbounded particle count on a phone that
        // is also decoding a language model.
        if (_active.value.size >= MAX_CONCURRENT) return false
        _active.value = _active.value + RewardBurst(nextId.getAndIncrement(), kind, count)
        return true
    }

    /** Called by the overlay when a burst's animation has run its course. */
    fun finished(id: Long) {
        _active.value = _active.value.filterNot { it.id == id }
    }

    private companion object {
        const val MAX_CONCURRENT = 3

        fun defaultCount(kind: RewardKind): Int = when (kind) {
            RewardKind.COIN -> 10
            RewardKind.STAR -> 12
            RewardKind.FLAKE -> 14
            RewardKind.MIX -> 22
        }
    }
}

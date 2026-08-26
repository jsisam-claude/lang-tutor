package org.sisam.langtutor.ui.reward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reward loop's state, which is the half of it that can be wrong silently.
 * Particles and chimes announce their own bugs; a bus that leaks bursts or
 * reuses an id just makes the screen stop celebrating one day.
 */
class RewardBusTest {

    @Test
    fun `each celebration is its own burst, with its own id`() {
        val bus = RewardBus()
        bus.celebrate(RewardKind.COIN)
        bus.celebrate(RewardKind.COIN)

        val ids = bus.active.value.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `bursts beyond the ceiling are refused, not queued`() {
        // A child tapping fast should get a livelier screen, not an unbounded
        // particle count on a phone that is also decoding a language model.
        val bus = RewardBus()
        repeat(3) { assertTrue(bus.celebrate(RewardKind.STAR)) }
        assertFalse(bus.celebrate(RewardKind.STAR))
        assertEquals(3, bus.active.value.size)
    }

    @Test
    fun `finishing one burst frees exactly one slot`() {
        val bus = RewardBus()
        repeat(3) { bus.celebrate(RewardKind.COIN) }
        val first = bus.active.value.first().id

        bus.finished(first)

        assertEquals(2, bus.active.value.size)
        assertFalse(bus.active.value.any { it.id == first })
        assertTrue(bus.celebrate(RewardKind.COIN))
    }

    @Test
    fun `ids are never reused after a burst is retired`() {
        // The id keys the animation: a reused one would make a new burst adopt
        // the finished one's progress and vanish on its first frame.
        val bus = RewardBus()
        val seen = mutableSetOf<Long>()
        repeat(20) {
            bus.celebrate(RewardKind.MIX)
            val id = bus.active.value.last().id
            assertTrue("id $id was handed out twice", seen.add(id))
            bus.finished(id)
        }
    }

    @Test
    fun `finishing an unknown id is a no-op`() {
        val bus = RewardBus()
        bus.celebrate(RewardKind.FLAKE)
        bus.finished(-1L)
        assertEquals(1, bus.active.value.size)
    }
}

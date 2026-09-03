package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sisam.langtutor.content.AlignCue
import org.sisam.langtutor.content.Twister

class DrillDeckTwisterTest {

    private fun tw(id: String, level: Int, en: String, he: String = "עברית", align: List<AlignCue>? = null) =
        Twister(id = id, level = level, sound = "th-voiceless", en = en, he = he, align = align)

    private val set = listOf(
        tw("t1", 1, "Three thin things.", align = listOf(AlignCue(listOf(0, 0), listOf(0, 0)))),
        tw("t2", 6, "The sixth sheep has thick socks."),
        tw("t3", 7, "Thirty thirsty thinkers thought all Thursday."),
    )

    @Test
    fun `a twister round keeps the authored order and every line`() {
        // The order is the teaching: a sound opens with its shortest line and
        // climbs. Shuffling or capping would break the climb, which is why
        // this round is the one that does neither.
        val round = DrillDeck.twisterRound(set)
        assertEquals(listOf("t1", "t2", "t3").size, round.size)
        assertEquals("Three thin things.", round.first().text)
        assertEquals("Thirty thirsty thinkers thought all Thursday.", round.last().text)
    }

    @Test
    fun `the authored Hebrew and cues travel with the line`() {
        val first = DrillDeck.twisterRound(set).first()
        assertEquals("עברית", first.hebrew)
        assertEquals(1, first.align?.size)
        assertNull(DrillDeck.twisterRound(set)[1].align)
    }

    @Test
    fun `length still buckets the item, so the pane sizes text the same way`() {
        val round = DrillDeck.twisterRound(set)
        assertEquals(DrillLevel.SHORT, round[0].level)
        assertEquals(DrillLevel.LONG, round[1].level)
    }

    @Test
    fun `an empty set is an empty round rather than a crash`() {
        assertEquals(emptyList<DrillItem>(), DrillDeck.twisterRound(emptyList()))
    }
}

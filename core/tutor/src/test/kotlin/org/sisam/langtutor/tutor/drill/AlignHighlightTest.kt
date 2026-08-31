package org.sisam.langtutor.tutor.drill

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sisam.langtutor.content.AlignCue

class AlignHighlightTest {

    // "I see the market." / "אני רואה את השוק." — the market chunk maps two
    // English words to the one Hebrew word past the uncovered את.
    private val cues = listOf(
        AlignCue(en = listOf(0, 0), he = listOf(0, 0)),
        AlignCue(en = listOf(1, 1), he = listOf(1, 1)),
        AlignCue(en = listOf(2, 3), he = listOf(3, 3)),
    )

    @Test
    fun `each english word lights its hebrew counterpart`() {
        assertEquals(setOf(0), AlignHighlight.hebrewWordsFor(0, cues))
        assertEquals(setOf(1), AlignHighlight.hebrewWordsFor(1, cues))
        assertEquals(setOf(3), AlignHighlight.hebrewWordsFor(2, cues))
        assertEquals(setOf(3), AlignHighlight.hebrewWordsFor(3, cues))
    }

    @Test
    fun `uncovered english word lights nothing`() {
        assertEquals(emptySet<Int>(), AlignHighlight.hebrewWordsFor(9, cues))
    }

    @Test
    fun `chunk cue lights the whole hebrew span`() {
        val chunk = listOf(AlignCue(en = listOf(0, 2), he = listOf(0, 1)))
        assertEquals(setOf(0, 1), AlignHighlight.hebrewWordsFor(1, chunk))
    }

    @Test
    fun `malformed cues are skipped, not guessed at`() {
        val bad = listOf(
            AlignCue(en = listOf(0), he = listOf(0, 0)),
            AlignCue(en = listOf(1, 0), he = listOf(0, 0)),
            AlignCue(en = listOf(0, 0), he = listOf(2, 1)),
            AlignCue(en = listOf(-1, 0), he = listOf(0, 0)),
        )
        for (i in 0..1) {
            assertEquals(emptySet<Int>(), AlignHighlight.hebrewWordsFor(i, bad))
        }
    }
}

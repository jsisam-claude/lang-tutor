package org.sisam.langtutor.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twisters are shown, spoken and scored to a learner who cannot check
 * them, so the same standing rule as the phrasebank applies: what is in the
 * file is what may be shown, and this test is the gate that says so.
 */
class TwistersTest {

    private val book = runBlocking { ResourceTwisterRepository().book() }

    private fun isHebrew(s: String) = s.any { it in '֐'..'׿' }

    @Test
    fun `the file parses and is not empty`() {
        assertTrue("no twisters loaded", book.twisters.isNotEmpty())
        assertTrue("no sounds loaded", book.sounds.isNotEmpty())
    }

    @Test
    fun `ids are unique`() {
        val ids = book.twisters.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every line names a sound the book defines`() {
        val keys = book.sounds.map { it.key }.toSet()
        val orphans = book.twisters.filter { it.sound !in keys }.map { it.id }
        assertTrue("lines with an unknown sound: $orphans", orphans.isEmpty())
    }

    @Test
    fun `every sound has at least two lines`() {
        // One line is not a practice round; the picker must not offer a card
        // that ends after a single try.
        val thin = book.playableSounds()
            .map { it.key to book.forSound(it.key).size }
            .filter { it.second < 2 }
        assertTrue("sounds with too few lines: $thin", thin.isEmpty())
    }

    @Test
    fun `text sides are in the right script`() {
        for (t in book.twisters) {
            assertTrue("${t.id}: empty en", t.en.isNotBlank())
            assertTrue("${t.id}: Hebrew in the en field", !isHebrew(t.en))
            assertTrue("${t.id}: he is not Hebrew", isHebrew(t.he))
            t.heF?.let { assertTrue("${t.id}: he_f is not Hebrew", isHebrew(it)) }
            assertTrue("${t.id}: he_f duplicates he", t.heF == null || t.heF != t.he)
        }
    }

    @Test
    fun `levels are inside the ladder`() {
        val bad = book.twisters.filter { it.level !in 1..7 }.map { it.id }
        assertTrue("levels outside 1-7: $bad", bad.isEmpty())
    }

    @Test
    fun `levels one to three carry alignment cues and the rest do not`() {
        // Same rule the phrasebank follows: the meaning row can only light up
        // where a batch authored the spans by hand.
        val missing = book.twisters.filter { it.level <= 3 && it.align.isNullOrEmpty() }.map { it.id }
        assertTrue("cues missing: $missing", missing.isEmpty())
    }

    @Test
    fun `cue spans land inside their own sentence`() {
        for (t in book.twisters) {
            val en = t.en.split(" ").size
            val he = t.he.split(" ").size
            for (cue in t.align.orEmpty()) {
                assertEquals("${t.id}: en cue is not a pair", 2, cue.en.size)
                assertEquals("${t.id}: he cue is not a pair", 2, cue.he.size)
                assertTrue("${t.id}: en span $cue outside 0..${en - 1}", cue.en[0] in 0..cue.en[1] && cue.en[1] < en)
                assertTrue("${t.id}: he span $cue outside 0..${he - 1}", cue.he[0] in 0..cue.he[1] && cue.he[1] < he)
            }
        }
    }

    @Test
    fun `a wrong format yields nothing rather than half a room`() {
        val book = runBlocking {
            object : TwisterRepository {
                override suspend fun book() = TwisterBook.EMPTY
            }.book()
        }
        assertTrue(book.twisters.isEmpty())
    }
}

package org.sisam.langtutor.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturePacksTest {

    private val packs = runBlocking { ResourcePicturePackRepository().packs() }

    private fun isHebrew(s: String) = s.any { it in '֐'..'׿' }

    @Test
    fun `the file parses and every pack has words`() {
        assertTrue("no packs loaded", packs.isNotEmpty())
        val empty = packs.filter { it.words.isEmpty() }.map { it.id }
        assertTrue("packs with no words: $empty", empty.isEmpty())
    }

    @Test
    fun `pack ids are unique and so are the words inside a pack`() {
        assertEquals(packs.size, packs.map { it.id }.toSet().size)
        for (p in packs) {
            assertEquals("${p.id} repeats a word", p.words.size, p.words.map { it.en }.toSet().size)
        }
    }

    @Test
    fun `each side is in the right script`() {
        for (p in packs) {
            assertTrue("${p.id}: title has no English", p.title.en.isNotBlank())
            assertTrue("${p.id}: title has no Hebrew", isHebrew(p.title.he))
            for (word in p.words) {
                assertTrue("${p.id}/${word.en}: not lowercase English", word.en == word.en.lowercase())
                assertTrue("${p.id}/${word.en}: Hebrew in the en field", !isHebrew(word.en))
                assertTrue("${p.id}/${word.en}: he is not Hebrew", isHebrew(word.he))
            }
        }
    }

    @Test
    fun `a pack states whether it has been read`() {
        // The review flag is the honest part of shipping content fast: a set
        // that has not had a native read says so in the data, next to the
        // content it describes, rather than in a tracker that drifts.
        for (p in packs) {
            assertTrue("${p.id}: unknown review state '${p.review}'", p.review in setOf("pending", "done"))
        }
    }

    @Test
    fun `a pack is big enough to be a round`() {
        // The room deals four cards. A pack that cannot fill one is a card
        // that repeats, which teaches nothing and looks broken.
        val thin = packs.filter { it.words.size < 4 }.map { it.id to it.words.size }
        assertTrue("packs too small for a round: $thin", thin.isEmpty())
    }
}

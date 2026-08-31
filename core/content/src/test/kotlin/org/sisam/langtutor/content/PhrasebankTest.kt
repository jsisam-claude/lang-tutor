package org.sisam.langtutor.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasebankTest {

    private val repository = ResourcePhrasebankRepository()

    @Test
    fun `all six themes load whole, 12 sentences per level each`() = runTest {
        val sentences = repository.sentences()
        assertEquals(504, sentences.size)
        for (level in 1..7) {
            assertEquals("level $level", 72, sentences.count { it.level == level })
        }
        assertEquals(sentences.size, sentences.map { it.id }.distinct().size)
    }

    @Test
    fun `a sentence round-trips with its variant and alignment`() = runTest {
        val bee = repository.sentences().first { it.id == "bee-l1-007" }
        assertEquals("I like honey.", bee.en)
        assertEquals("אני אוהב דבש.", bee.he)
        // Feminine first person differs, so the variant is present.
        assertEquals("אני אוהבת דבש.", bee.heF)
        val align = checkNotNull(bee.align)
        assertEquals(3, align.size)
        assertEquals(listOf(0, 0), align.first().en)
    }

    @Test
    fun `batch three themes are present`() = runTest {
        val home = repository.sentences().first { it.id == "hom-l1-005" }
        assertEquals("I like my bed.", home.en)
        // Feminine first person differs, so the variant is present.
        assertEquals("אני אוהבת את המיטה שלי.", home.heF)
        checkNotNull(home.align)
    }

    @Test
    fun `upper levels ship unaligned by design`() = runTest {
        val upper = repository.sentences().filter { it.level >= 4 }
        assertTrue(upper.isNotEmpty())
        upper.forEach { assertNull("expected no align on ${it.id}", it.align) }
    }
}

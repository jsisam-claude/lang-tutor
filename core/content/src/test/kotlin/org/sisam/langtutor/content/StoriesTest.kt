package org.sisam.langtutor.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that makes "this story fits the learner" a fact rather than a
 * claim (docs/short-stories.md).
 *
 * The load-bearing test is the first one: every word of every story must be
 * one the phrasebank already teaches at or below that story's Level. A story
 * that reaches for one word the learner has not met fails here, by name, with
 * the Level that word first appears at — so the fix is either to change the
 * word or to raise the story, and both are one line.
 */
class StoriesTest {

    private val stories = runBlocking { ResourceStoryRepository().stories() }
    private val sentences = runBlocking { ResourcePhrasebankRepository().sentences() }

    private fun isHebrew(s: String) = s.any { it in '֐'..'׿' }

    @Test
    fun `every word of every story is one the phrasebank has taught`() {
        // The whole reason the file can exist: no word arrives before the
        // bank has introduced it, which is how a decodable reader is built.
        val firstLevel = buildMap<String, Int> {
            for (s in sentences) for (w in TaughtWords.tokens(s.en)) {
                merge(w, s.level) { a, b -> minOf(a, b) }
            }
        }
        for (story in stories) {
            val taught = TaughtWords.upTo(sentences, story.level)
            val unseen = story.pages
                .flatMap { TaughtWords.tokens(it.en) }
                .filter { it !in taught }
                .distinct()
                .map { "$it (first taught at Level ${firstLevel[it] ?: "never"})" }
            assertTrue(
                "${story.id} is Level ${story.level} but reaches for: $unseen",
                unseen.isEmpty(),
            )
        }
    }

    @Test
    fun `the file parses and every story is a story`() {
        assertTrue("no stories loaded", stories.isNotEmpty())
        assertEquals("ids are not unique", stories.size, stories.map { it.id }.toSet().size)
        for (story in stories) {
            assertTrue("${story.id}: level ${story.level}", story.level in 1..7)
            // Fewer than four pages is an anecdote; more than ten is a
            // chapter, and neither is what this room is for.
            assertTrue("${story.id}: ${story.pages.size} pages", story.pages.size in 4..10)
            assertTrue("${story.id}: id is not kebab-case", story.id.matches(Regex("[a-z0-9-]+")))
        }
    }

    @Test
    fun `each side is in the right script, and the feminine only where it differs`() {
        for (story in stories) {
            assertTrue("${story.id}: title has no English", story.title.en.isNotBlank())
            assertTrue("${story.id}: title has no Hebrew", isHebrew(story.title.he))
            story.pages.forEachIndexed { i, page ->
                assertTrue("${story.id} p$i: Hebrew in the en field", !isHebrew(page.en))
                assertTrue("${story.id} p$i: he is not Hebrew", isHebrew(page.he))
                assertTrue("${story.id} p$i: en is blank", page.en.isNotBlank())
                // A variant identical to the masculine is noise: it says the
                // forms differ when they do not.
                assertTrue("${story.id} p$i: he_f is the same as he", page.heF != page.he)
                page.heF?.let { assertTrue("${story.id} p$i: he_f is not Hebrew", isHebrew(it)) }
            }
        }
    }

    @Test
    fun `a story says whether it has been read`() {
        for (story in stories) {
            assertTrue("${story.id}: unknown review state '${story.review}'", story.review in setOf("pending", "done"))
        }
    }

    @Test
    fun `nothing anywhere frames the reader by age`() {
        // The audience is Levels 1-7 at every age, so a story that calls its
        // reader a child excludes half of them (docs/learner-levels.md).
        val banned = listOf(
            "kids", "children", "child", "boys and girls", "little one", "kiddo",
            "ילדים", "ילדה קטנה", "ילד קטן",
        )
        for (story in stories) {
            val text = (story.title.en + " " + story.title.he + " " +
                story.pages.joinToString(" ") { it.en + " " + it.he + " " + (it.heF ?: "") }).lowercase()
            for (word in banned) {
                assertTrue("${story.id} frames its reader by age: '$word'", !text.contains(word))
            }
        }
    }
}

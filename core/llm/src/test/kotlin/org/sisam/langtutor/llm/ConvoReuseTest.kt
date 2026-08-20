package org.sisam.langtutor.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoReuseTest {

    private val sys = "You are Tuki. Lesson: colors."
    private val seen = listOf("hello", "Hi! What colour is it?", "red", "Great! And this one?")
    private val base = ConvoState(sys, 0.7f, seen, estTokens = 600, dirty = false)

    @Test
    fun `plain continuation reuses`() {
        assertTrue(ConvoReuse.canReuse(base, sys, 0.7f, seen))
    }

    @Test
    fun `sliding window is a suffix, so it still reuses`() {
        // Past HISTORY_TURNS the caller sends only a trailing slice.
        assertTrue(ConvoReuse.canReuse(base, sys, 0.7f, seen.takeLast(2)))
        assertTrue(ConvoReuse.canReuse(base, sys, 0.7f, emptyList()))
    }

    @Test
    fun `a turn the model never saw forces a rebuild`() {
        // The scripted AskRepeat branch appends to the transcript without ever
        // calling the LLM. Same count as a real turn — different content.
        val withSkipped = seen.dropLast(1) + "wed" + "Let's try that again!"
        assertFalse(
            "an utterance the conversation never processed must not be skipped over",
            ConvoReuse.canReuse(base, sys, 0.7f, withSkipped),
        )
    }

    @Test
    fun `a substituted reply forces a rebuild`() {
        val substituted = seen.dropLast(1) + "Let's get back to our lesson!"
        assertFalse(ConvoReuse.canReuse(base, sys, 0.7f, substituted))
    }

    @Test
    fun `window longer than what the conversation saw rebuilds`() {
        assertFalse(ConvoReuse.canReuse(base, sys, 0.7f, listOf("x") + seen))
    }

    @Test
    fun `no conversation, lesson switch, temperature change, dirty, or full context all rebuild`() {
        assertFalse(ConvoReuse.canReuse(null, sys, 0.7f, seen))
        assertFalse(ConvoReuse.canReuse(base, "You are Tuki. Lesson: animals.", 0.7f, seen))
        assertFalse(ConvoReuse.canReuse(base, sys, 0.2f, seen))
        assertFalse(ConvoReuse.canReuse(base.copy(dirty = true), sys, 0.7f, seen))
        assertFalse(ConvoReuse.canReuse(base.copy(estTokens = ConvoReuse.MAX_EST_TOKENS + 1), sys, 0.7f, seen))
    }

    @Test
    fun `isSuffix edge cases`() {
        assertTrue(ConvoReuse.isSuffix(emptyList(), listOf("a")))
        assertTrue(ConvoReuse.isSuffix(listOf("a"), listOf("a")))
        assertFalse(ConvoReuse.isSuffix(listOf("a"), listOf("a", "b")))  // prefix is NOT a suffix
        assertTrue(ConvoReuse.isSuffix(listOf("b"), listOf("a", "b")))
    }
}

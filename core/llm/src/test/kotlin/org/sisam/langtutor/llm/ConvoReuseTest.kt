package org.sisam.langtutor.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoReuseTest {

    private val base = ConvoState(
        systemText = "You are Tuki. Lesson: colors.",
        temperature = 0.7f,
        priorCount = 5,
        estTokens = 600,
        dirty = false,
    )

    @Test
    fun `plain continuation reuses`() {
        assertTrue(ConvoReuse.canReuse(base, base.systemText, 0.7f, priorCount = 7))
    }

    @Test
    fun `sliding history window keeps the same prior count and still reuses`() {
        // Past HISTORY_TURNS the orchestrator's window stops growing.
        assertTrue(ConvoReuse.canReuse(base, base.systemText, 0.7f, priorCount = 5))
    }

    @Test
    fun `no conversation yet means rebuild`() {
        assertFalse(ConvoReuse.canReuse(null, base.systemText, 0.7f, priorCount = 1))
    }

    @Test
    fun `lesson switch changes the system text and rebuilds`() {
        assertFalse(ConvoReuse.canReuse(base, "You are Tuki. Lesson: animals.", 0.7f, 7))
    }

    @Test
    fun `temperature change rebuilds`() {
        assertFalse(ConvoReuse.canReuse(base, base.systemText, 0.2f, 7))
    }

    @Test
    fun `shrunken history means a new session and rebuilds`() {
        assertFalse(ConvoReuse.canReuse(base, base.systemText, 0.7f, priorCount = 1))
    }

    @Test
    fun `dirty conversation after a cut stream rebuilds`() {
        assertFalse(ConvoReuse.canReuse(base.copy(dirty = true), base.systemText, 0.7f, 7))
    }

    @Test
    fun `context budget exhausted rebuilds`() {
        val fat = base.copy(estTokens = ConvoReuse.MAX_EST_TOKENS + 1)
        assertFalse(ConvoReuse.canReuse(fat, base.systemText, 0.7f, 7))
    }
}

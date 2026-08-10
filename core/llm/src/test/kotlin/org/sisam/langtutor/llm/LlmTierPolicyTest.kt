package org.sisam.langtutor.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTierPolicyTest {

    private val e4b = "models/gemma-4-E4B-it.litertlm"
    private val e2b = "models/gemma-4-E2B-it.litertlm"

    @Test
    fun `roomy Pixel 9 gets the quality tier`() {
        val c = LlmTierPolicy.choose(listOf(e4b, e2b), availGb = 6.2f)!!
        assertEquals(e4b, c.path)
        assertEquals("E4B", c.tierLabel)
        assertFalse(c.tight)
        assertTrue(c.reason, "6.2" in c.reason && "4.5" in c.reason)
    }

    @Test
    fun `busy Pixel 9 falls back to E2B with the numbers in the reason`() {
        val c = LlmTierPolicy.choose(listOf(e4b, e2b), availGb = 3.6f)!!
        assertEquals(e2b, c.path)
        assertEquals("E2B", c.tierLabel)
        assertFalse(c.tight)
        // The reason must explain what was skipped and why.
        assertTrue(c.reason, "E4B" in c.reason && "3.6" in c.reason && "4.5" in c.reason)
    }

    @Test
    fun `desperate memory still loads the smallest model, marked tight`() {
        val c = LlmTierPolicy.choose(listOf(e4b, e2b), availGb = 1.8f)!!
        assertEquals(e2b, c.path)
        assertTrue(c.tight)
        assertTrue(c.reason, "tight" in c.reason)
    }

    @Test
    fun `only E4B installed and memory tight - still loads it rather than nothing`() {
        val c = LlmTierPolicy.choose(listOf(e4b), availGb = 2.0f)!!
        assertEquals(e4b, c.path)
        assertTrue(c.tight)
    }

    @Test
    fun `unknown model names are never vetoed`() {
        val exotic = "models/some-experimental.litertlm"
        val c = LlmTierPolicy.choose(listOf(exotic), availGb = 0.5f)!!
        assertEquals(exotic, c.path)
        assertFalse(c.tight)
        assertEquals("some-experimental.litertlm", c.tierLabel)
    }

    @Test
    fun `nothing installed means no choice`() {
        assertNull(LlmTierPolicy.choose(emptyList(), availGb = 8f))
    }
}

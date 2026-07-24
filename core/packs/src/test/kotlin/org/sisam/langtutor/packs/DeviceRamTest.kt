package org.sisam.langtutor.packs

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRamTest {

    @Test
    fun `totalMem maps to the marketing RAM tier`() {
        // Typical reported totalMem (a bit under physical) for each tier.
        assertEquals(8, ramTierGb(8_260_000_000L))   // Pixel 9a class (~7.7 GiB)
        assertEquals(12, ramTierGb(12_000_000_000L)) // Pixel 9 (~11.2 GiB)
        assertEquals(16, ramTierGb(16_300_000_000L)) // Pixel 9 Pro (~15.2 GiB)
        assertEquals(6, ramTierGb(6_100_000_000L))
    }

    @Test
    fun `each supported Pixel is offered the right model tier`() {
        val repo = FakePackRepository()
        fun modelsFor(bytes: Long) =
            repo.eligiblePacks(ramTierGb(bytes)).filter { it.kind == PackKind.LLM }.map { it.id }.toSet()

        val pixel9a = modelsFor(8_260_000_000L)  // 8 GB
        val pixel9 = modelsFor(12_000_000_000L)  // 12 GB
        val pixel10ProXl = modelsFor(16_300_000_000L) // 16 GB

        // 9a: base E2B only — no 12/16 GB models.
        assertEquals(setOf("llm-base-e2b"), pixel9a)
        // 9: base + quality E4B (not the 16 GB-only advanced pack).
        assertEquals(setOf("llm-base-e2b", "llm-quality-e4b"), pixel9)
        // Pro XL: everything, including the experimental 8B.
        assertEquals(setOf("llm-base-e2b", "llm-quality-e4b", "llm-advanced-8b"), pixel10ProXl)
    }
}

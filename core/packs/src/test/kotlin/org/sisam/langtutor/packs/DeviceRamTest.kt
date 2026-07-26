package org.sisam.langtutor.packs

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRamTest {

    @Test
    fun `totalMem maps to the marketing RAM tier`() {
        // Devices report totalMem well below physical (kernel/GPU reserves).
        // LOW values are the realistic ones — they must still hit the right tier.
        assertEquals(8, ramTierGb(7_400_000_000L))   // Pixel 9a reporting low
        assertEquals(8, ramTierGb(8_260_000_000L))   // Pixel 9a reporting high
        assertEquals(12, ramTierGb(11_100_000_000L)) // Pixel 9 reporting low
        assertEquals(12, ramTierGb(12_000_000_000L))
        assertEquals(16, ramTierGb(15_100_000_000L)) // Pixel 10 Pro XL reporting low
        assertEquals(16, ramTierGb(16_300_000_000L))
        assertEquals(6, ramTierGb(6_100_000_000L))
    }

    @Test
    fun `each supported Pixel is offered the right model tier`() {
        val repo = FakePackRepository()
        fun modelsFor(bytes: Long) =
            repo.eligiblePacks(ramTierGb(bytes)).filter { it.kind == PackKind.LLM }.map { it.id }.toSet()

        // Use the LOW realistic totalMem readings — the case naive rounding broke.
        val pixel9a = modelsFor(7_400_000_000L)       // 8 GB device
        val pixel9 = modelsFor(11_100_000_000L)       // 12 GB device
        val pixel10ProXl = modelsFor(15_100_000_000L) // 16 GB device

        // 9a: base E2B only — no 12 GB+ models.
        assertEquals(setOf("llm-base-e2b"), pixel9a)
        // 9 and Pro XL: base + quality E4B.
        assertEquals(setOf("llm-base-e2b", "llm-quality-e4b"), pixel9)
        assertEquals(setOf("llm-base-e2b", "llm-quality-e4b"), pixel10ProXl)
    }
}

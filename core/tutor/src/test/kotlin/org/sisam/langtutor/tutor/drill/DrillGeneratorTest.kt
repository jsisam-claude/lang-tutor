package org.sisam.langtutor.tutor.drill

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.Role

/**
 * The generator's promise is not that the model behaves — it is that nothing
 * the model does gets past the gauntlet. So most of this tests [parse] on
 * hostile output.
 */
class DrillGeneratorTest {

    private val gen = DrillGenerator(FakeLlmEngine())

    @Test
    fun `clean output parses to items at the level`() {
        val items = gen.parse("My dog is big.\nWe play outside!\nThe sun is hot.", DrillLevel.SHORT, 6)
        assertEquals(3, items.size)
        assertTrue(items.all { it.level == DrillLevel.SHORT })
    }

    @Test
    fun `numbering, bullets and quotes are furniture, not content`() {
        val items = gen.parse("1. My dog is big.\n- We play outside!\n\"The sun is hot.\"", DrillLevel.SHORT, 6)
        assertEquals(listOf("My dog is big.", "We play outside!", "The sun is hot."), items.map { it.text })
    }

    @Test
    fun `lines in the wrong bucket are dropped, not re-leveled`() {
        // A preamble and an over-long sentence both miss the SHORT bucket.
        val raw = "Here are some sentences for you\nMy dog is big.\nThe little dog runs across the green park."
        val items = gen.parse(raw, DrillLevel.SHORT, 6)
        assertEquals(listOf("My dog is big."), items.map { it.text })
    }

    @Test
    fun `digits, Hebrew, and unsafe lines never survive`() {
        val raw = "I have 3 cats.\nשלום לך\nPlay with the gun!\nMy cat is small."
        val items = gen.parse(raw, DrillLevel.SHORT, 6)
        assertEquals(listOf("My cat is small."), items.map { it.text })
    }

    @Test
    fun `the format examples cannot be echoed back as content`() {
        val items = gen.parse("The cat sleeps.\nMy fish swims fast.", DrillLevel.SHORT, 6)
        assertEquals(listOf("My fish swims fast."), items.map { it.text })
    }

    @Test
    fun `duplicates collapse on words and the count is a cap`() {
        val raw = "My dog is big.\nmy dog is BIG!\nWe play outside.\nThe sun is hot.\nBirds sing songs."
        val items = gen.parse(raw, DrillLevel.SHORT, 3)
        assertEquals(3, items.size)
        assertEquals("My dog is big.", items.first().text)
    }

    @Test
    fun `generate loads the model and asks for the topic, count and level`() = runTest {
        val llm = FakeLlmEngine(listOf("My dog is big.\nWe play outside!"))
        val generator = DrillGenerator(llm)

        val items = generator.generate(DrillLevel.SHORT, 6, Random(3))

        assertTrue(llm.loaded)
        assertEquals(listOf("My dog is big.", "We play outside!"), items.map { it.text })
        val request = llm.calls.single()
        val task = request.messages.last()
        assertEquals(Role.USER, task.role)
        assertTrue(task.text.contains("6"))
        assertTrue(task.text.contains("2 to 4 words"))
        assertTrue(DrillGenerator.TOPICS.any { it in task.text })
        assertTrue(request.systemPrompt.contains("Hebrew-speaking"))
        assertEquals(96, request.maxTokens)
    }

    @Test
    fun `a generation failure returns empty, so the caller reaches the deck`() = runTest {
        val broken = object : org.sisam.langtutor.llm.LlmEngine {
            override suspend fun load(spec: org.sisam.langtutor.llm.LlmModelSpec) = Unit
            override fun generate(request: org.sisam.langtutor.llm.LlmRequest) =
                kotlinx.coroutines.flow.flow<org.sisam.langtutor.llm.LlmEvent> {
                    throw IllegalStateException("engine fell over")
                }
            override suspend fun unload() = Unit
        }
        assertEquals(emptyList<DrillItem>(), DrillGenerator(broken).generate(DrillLevel.WORDS, 8, Random(1)))
    }
}

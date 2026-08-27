package org.sisam.langtutor.tutor.drill

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.safety.BlocklistSafetyFilter
import org.sisam.langtutor.safety.SafetyFilter

/**
 * Writes fresh drill lines with the LLM, so the vocabulary room is not the
 * same eighty curriculum lines forever.
 *
 * The model is the WRITER, never the judge: it produces candidate lines, and
 * everything it produces goes through the same gauntlet regardless —
 * bucket-checked against the requested level, charset-checked to plain
 * English, safety-filtered line by line, deduped. A line that fails any check
 * is silently dropped, and a generation that fails entirely returns empty so
 * the caller falls back to the curriculum deck. The room never depends on the
 * model behaving; it only benefits when it does.
 *
 * The prompt carries the actual pedagogy, so its rules are worth stating:
 *
 * - **Concrete, imageable, high-frequency words.** A pre-A1 learner repeats
 *   what they can picture; "the cat sleeps" drills better than "time passes".
 * - **Vary the opening word.** Small models love a frame; eight lines of
 *   "I like X" is one drill pretending to be eight.
 * - **Vary the sounds.** The round should wander the phoneme space, not sit
 *   on one vowel.
 * - **No names, no brands, no digits.** Names phonemize badly and drill
 *   nothing; digits come back from ASR as "3" and fail the word match even
 *   when said perfectly, so numbers must arrive spelled out.
 * - **A topic seed per round**, drawn from the child's world, so consecutive
 *   rounds differ even under near-greedy decoding.
 * - **Format examples, then "new"**: the examples pin the output shape; the
 *   word "new" plus a parse-time filter keeps them from being echoed back.
 */
class DrillGenerator(
    private val llm: LlmEngine,
    private val safety: SafetyFilter = BlocklistSafetyFilter(),
) {

    /** Start the (idempotent) model load early; [generate] also ensures it. */
    suspend fun prepare() {
        llm.load(LlmModelSpec(modelId = "drill"))
    }

    /** Non-suspend release for ViewModel.onCleared(), same shape as the rooms'. */
    fun shutdown() {
        CoroutineScope(Dispatchers.Default).launch { llm.unload() }
    }

    /**
     * Up to [count] fresh items at [level]; possibly fewer, possibly none —
     * the caller tops up from the curriculum deck.
     */
    suspend fun generate(level: DrillLevel, count: Int, random: Random): List<DrillItem> {
        val topic = TOPICS[random.nextInt(TOPICS.size)]
        var out = ""
        try {
            llm.load(LlmModelSpec(modelId = "drill"))
            llm.generate(request(level, count, topic)).collect { event ->
                when (event) {
                    is LlmEvent.Token -> out += event.text
                    is LlmEvent.Done -> out = event.fullText
                }
            }
        } catch (e: Exception) {
            // A failed generation must not fail the round — the deck exists.
            println("DrillGenerator: generation failed: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        return parse(out, level, count)
    }

    /** The gauntlet. Internal so tests can feed it raw model output directly. */
    internal fun parse(raw: String, level: DrillLevel, count: Int): List<DrillItem> {
        val seen = mutableSetOf<List<String>>()
        val items = mutableListOf<DrillItem>()
        for (rawLine in raw.lines()) {
            var line = rawLine.trim()
                .removePrefix("-").removePrefix("*").removePrefix("•")
                .replace(NUMBERING, "")
                .trim()
                .trim('"', '“', '”', '‘', '’')
                .trim()
            if (line.isEmpty()) continue
            // The format examples must never come back as content.
            if (line in EXAMPLE_LINES) continue
            // Plain sayable English only: no digits, no Hebrew, no emoji, no
            // markdown furniture. isLetter() alone would wave Hebrew through.
            if (!line.all { it in ALLOWED_CHARS }) continue
            val words = WordMatch.tokens(line)
            if (words.isEmpty()) continue
            if (DrillDeck.classify(line) != level) continue
            if (!safety.check(line).allowed) continue
            if (!seen.add(words)) continue
            items += DrillItem(line, level)
            if (items.size >= count) break
        }
        return items
    }

    private fun request(level: DrillLevel, count: Int, topic: String): LlmRequest {
        val task = when (level) {
            DrillLevel.WORDS -> """
                Write $count different single English words about $topic, for the child to say aloud.
                Rules:
                - Exactly one word per line and nothing else: no numbering, no bullets, no translations, no explanations.
                - Concrete words the child can picture: things, animals, colors, simple actions.
                - Only very common words a beginner learns first.
                Example format:
                ${EXAMPLES_WORDS.joinToString("\n                ")}
                Now write $count new words about $topic.
            """.trimIndent()

            DrillLevel.SHORT -> """
                Write $count different short English sentences about $topic, for the child to repeat aloud.
                Rules:
                - Each sentence is 2 to 4 words, on its own line, ending with . or !
                - Present tense. Only simple, concrete words a five-year-old knows.
                - Start each sentence with a different word.
                - Across the set, use many different sounds.
                - Never use digits: write numbers as words. No names of people or brands.
                - Nothing else in your answer: no numbering, no bullets, no explanations.
                Example format:
                ${EXAMPLES_SHORT.joinToString("\n                ")}
                Now write $count new sentences about $topic.
            """.trimIndent()

            DrillLevel.LONG -> """
                Write $count different English sentences about $topic, for the child to repeat aloud.
                Rules:
                - Each sentence is 5 to 8 words, on its own line, ending with . or !
                - Present tense. Only simple, concrete words a young beginner knows.
                - Start each sentence with a different word.
                - Across the set, use many different sounds.
                - Never use digits: write numbers as words. No names of people or brands.
                - Nothing else in your answer: no numbering, no bullets, no explanations.
                Example format:
                ${EXAMPLES_LONG.joinToString("\n                ")}
                Now write $count new sentences about $topic.
            """.trimIndent()
        }
        return LlmRequest(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(ChatMessage(Role.USER, task)),
            maxTokens = when (level) {
                DrillLevel.WORDS -> 64
                DrillLevel.SHORT -> 96
                DrillLevel.LONG -> 128
            },
        )
    }

    companion object {
        val SYSTEM_PROMPT = """
            You write speaking-practice lines for Tuki, an English tutor for Hebrew-speaking children.
            Every line you write will be spoken by the tutor and repeated aloud by a child, so each
            line must be natural to say, warm, and safe. Use only simple high-frequency English.
            Never mention violence, fear, illness, brands, or grown-up topics. English only.
        """.trimIndent()

        /** The child's world, one seed per round, so rounds differ even under
         *  near-greedy decoding. */
        val TOPICS = listOf(
            "animals", "food", "colors and toys", "the family", "the body",
            "clothes", "school", "the park", "the weather", "bedtime",
            "birthdays", "the sea",
        )

        val EXAMPLES_WORDS = listOf("ball", "dog", "red")
        val EXAMPLES_SHORT = listOf("The cat sleeps.", "I like apples!")
        val EXAMPLES_LONG = listOf("The little dog runs to me.", "We eat bread in the morning.")
        val EXAMPLE_LINES: Set<String> =
            (EXAMPLES_WORDS + EXAMPLES_SHORT + EXAMPLES_LONG).toSet()

        private val NUMBERING = Regex("""^\d+[.)]?\s*""")
        private val ALLOWED_CHARS =
            (('a'..'z') + ('A'..'Z') + listOf(' ', '\'', '’', ',', '.', '!', '?', '-')).toSet()
    }
}

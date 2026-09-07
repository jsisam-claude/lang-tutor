package org.sisam.langtutor.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTokenizerTest {

    // Goldens generated with HF WhisperTokenizer (openai/whisper-medium).
    @Test
    fun `decode matches the reference tokenizer`() {
        assertEquals("Hello, world.", WhisperTokenizer.decode(intArrayOf(15947, 11, 1002, 13)))
        assertEquals(" I see a red ball!", WhisperTokenizer.decode(intArrayOf(286, 536, 257, 2182, 2594, 0)))
        assertEquals(
            "It's 3 o'clock — let's go?",
            WhisperTokenizer.decode(intArrayOf(3522, 311, 805, 277, 6, 9023, 3466, 718, 311, 352, 30)),
        )
    }

    @Test
    fun `special tokens are skipped`() {
        val withSpecials = intArrayOf(
            WhisperTokenizer.SOT, WhisperTokenizer.LANG_EN, WhisperTokenizer.TRANSCRIBE,
            WhisperTokenizer.NO_TIMESTAMPS, 15947, 11, 1002, 13, WhisperTokenizer.EOT,
        )
        assertEquals("Hello, world.", WhisperTokenizer.decode(withSpecials))
    }

    @Test
    fun `greedy loop appends until EOT and strips the prefix`() {
        // Scripted logits: emit token 100, then 200, then EOT.
        val script = intArrayOf(100, 200, WhisperTokenizer.EOT)
        var step = 0
        val decoder = WhisperGreedyDecoder { tokens, count ->
            assertEquals(WhisperTokenizer.SOT, tokens[0])
            assertEquals(WhisperGreedyDecoder.PREFIX + step, count)
            FloatArray(WhisperTokenizer.VOCAB_SIZE + 1).also { it[script[step++]] = 10f }
        }
        val ids = decoder.transcribe().ids
        assertEquals(listOf(100, 200), ids.toList())
    }

    // ---- English-only (.en) layout, used by the short-window ACFT exports ----

    @Test
    fun `english layout decodes real ACFT model output`() {
        // These ids came out of the actual acft_whisper_small.en_10s graph for
        // our own voice saying "I see a red ball" (docs/asr-model-eval.md).
        val en = WhisperTokenizer.of(WhisperLayout.ENGLISH)
        assertEquals(" I see a red ball.", en.decode(intArrayOf(314, 766, 257, 2266, 2613, 13)))
    }

    @Test
    fun `layout is chosen from the model's own vocab size`() {
        assertEquals(WhisperLayout.ENGLISH, WhisperLayout.forVocabSize(51_864))
        assertEquals(WhisperLayout.MULTILINGUAL, WhisperLayout.forVocabSize(51_865))
        // The two layouts disagree on every special id — that's the whole point.
        assertEquals(50_256, WhisperLayout.ENGLISH.eot)
        assertEquals(50_257, WhisperLayout.MULTILINGUAL.eot)
        assertArrayEquals(intArrayOf(50_257, 50_362), WhisperLayout.ENGLISH.prompt)
    }

    @Test
    fun `greedy loop uses the prompt and EOT of its layout`() {
        val layout = WhisperLayout.ENGLISH
        var step = 0
        val script = intArrayOf(314, 766, layout.eot)
        val ids = WhisperGreedyDecoder(layout = layout) { tokens, count ->
            if (step == 0) {
                // The English prompt is two tokens, not the multilingual four.
                assertEquals(layout.prompt[0], tokens[0])
                assertEquals(layout.prompt[1], tokens[1])
                assertEquals(layout.prompt.size, count)
            }
            FloatArray(layout.vocabSize).also { it[script[step++]] = 10f }
        }.transcribe().ids
        assertArrayEquals(intArrayOf(314, 766), ids)
    }

    // --- the encode side: a prompt for the decoder --------------------------

    private val en = WhisperTokenizer.of(WhisperLayout.ENGLISH)

    @Test
    fun `encode matches the reference tokenizer where greedy and BPE agree`() {
        // Goldens from the HF tokenizer for openai/whisper-small.en.
        assertArrayEquals(intArrayOf(4231, 345, 7477, 30), en.encode(" Are you OK?"))
        assertArrayEquals(intArrayOf(314, 766, 33158, 13), en.encode(" I see cereal."))
        assertArrayEquals(intArrayOf(2094, 470, 3638, 262, 3024, 27635, 13), en.encode(" Don't touch the hot stove."))
        assertArrayEquals(intArrayOf(1867, 338, 287, 534, 6131, 30), en.encode(" What's in your bag?"))
        assertArrayEquals(intArrayOf(9368, 9353, 13), en.encode(" Ten fingers."))
        assertArrayEquals(
            intArrayOf(10127, 262, 6193, 318, 5814, 393, 4692, 11, 356, 481, 711, 2354, 13),
            en.encode(" Whether the weather is warm or cold, we will play outside."),
        )
    }

    @Test
    fun `where greedy differs from BPE it still spells the same text`() {
        // BPE: 7683, 20161, 1102, 274, 13 ("pine", "con", "es"); greedy takes
        // a longer first piece. The decoder is shown the same words.
        for (text in listOf(" Three pinecones.", " The grasshopper's legs are long.", " José and Noa.")) {
            assertEquals(text, en.decode(en.encode(text)))
        }
        assertEquals("", en.decode(en.encode("")))
    }

    @Test
    fun `a prompt goes between startofprev and the layout's own prefix`() {
        val layout = WhisperLayout.ENGLISH
        val prompt = intArrayOf(4231, 345, 7477, 30)
        val script = intArrayOf(100, layout.eot)
        var step = 0
        var seen: IntArray? = null
        val decoder = WhisperGreedyDecoder(layout = layout, prompt = prompt) { tokens, count ->
            if (seen == null) seen = tokens.copyOf(count)
            FloatArray(layout.vocabSize).also { it[script[step++]] = 10f }
        }
        val ids = decoder.transcribe().ids
        assertEquals(listOf(100), ids.toList())
        assertArrayEquals(
            intArrayOf(layout.sotPrev, 4231, 345, 7477, 30, 50_257, 50_362),
            seen,
        )
    }

    @Test
    fun `a long prompt is cut to leave room for the transcript`() {
        val layout = WhisperLayout.ENGLISH
        val prompt = IntArray(200) { 1000 + it }
        var first: IntArray? = null
        WhisperGreedyDecoder(maxTokens = 128, layout = layout, prompt = prompt) { tokens, count ->
            if (first == null) first = tokens.copyOf(count)
            FloatArray(layout.vocabSize).also { it[layout.eot] = 10f }
        }.transcribe()
        val prefix = first!!
        assertEquals(128 - WhisperGreedyDecoder.RESERVE, prefix.size)
        assertEquals(layout.sotPrev, prefix[0])
        assertEquals(50_362, prefix.last())
    }

    @Test
    fun `no prompt is the decoder as it was`() {
        val layout = WhisperLayout.ENGLISH
        var first: IntArray? = null
        WhisperGreedyDecoder(layout = layout, prompt = intArrayOf()) { tokens, count ->
            if (first == null) first = tokens.copyOf(count)
            FloatArray(layout.vocabSize).also { it[layout.eot] = 10f }
        }.transcribe()
        assertArrayEquals(layout.prompt, first)
    }
}

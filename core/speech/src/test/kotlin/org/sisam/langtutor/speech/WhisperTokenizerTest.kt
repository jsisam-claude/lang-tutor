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
}

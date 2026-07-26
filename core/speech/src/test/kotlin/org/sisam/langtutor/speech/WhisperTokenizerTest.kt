package org.sisam.langtutor.speech

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
        val ids = decoder.transcribe()
        assertEquals(listOf(100, 200), ids.toList())
    }
}

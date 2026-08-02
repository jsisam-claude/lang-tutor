package org.sisam.langtutor.speech

/**
 * Which Whisper token layout an export uses. Chosen at runtime from the
 * model's own decode-output vocab size, because the two families disagree on
 * every special id:
 *
 *  - MULTILINGUAL (51_865): the classic layout, prompt
 *    `<|sot|> <|en|> <|transcribe|> <|notimestamps|>`.
 *  - ENGLISH (51_864): the `.en` exports (incl. the ACFT short-window models)
 *    have no language/task tokens at all — prompt is `<|sot|> <|notimestamps|>`
 *    and even `<|endoftext|>` moves.
 *
 * Feeding one layout's prompt to the other model produces confident garbage,
 * so this is never guessed.
 */
enum class WhisperLayout(
    val vocabSize: Int,
    val eot: Int,
    val prompt: IntArray,
    internal val resource: String,
) {
    MULTILINGUAL(
        vocabSize = 51_865,
        eot = 50_257,
        prompt = intArrayOf(50_258, 50_259, 50_359, 50_363),
        resource = "whisper/vocab.txt",
    ),
    ENGLISH(
        vocabSize = 51_864,
        eot = 50_256,
        prompt = intArrayOf(50_257, 50_362),
        resource = "whisper/vocab-en.txt",
    ),
    ;

    companion object {
        /** @param modelVocabSize the decode signature's output vocab dimension. */
        fun forVocabSize(modelVocabSize: Int): WhisperLayout =
            entries.firstOrNull { it.vocabSize == modelVocabSize } ?: MULTILINGUAL
    }
}

/**
 * Whisper tokenizer — DECODE side only (the ASR loop never needs to encode
 * text). Byte-level BPE: token strings from the bundled vocab are sequences of
 * GPT-2 "byte unicode" characters; decoding maps each char back to its raw byte
 * and interprets the result as UTF-8.
 *
 * Vocab resources are one token per line, line number = id, with \n \r \\
 * escaped: `whisper/vocab.txt` (multilingual, from openai/whisper-medium) and
 * `whisper/vocab-en.txt` (English-only, from openai/whisper-small.en). Both are
 * golden-tested against real model output in WhisperTokenizerTest.
 */
class WhisperTokenizer private constructor(
    val layout: WhisperLayout,
    private val vocab: Array<String>,
) {

    /** Decode token ids to text, skipping special/timestamp tokens. */
    fun decode(ids: IntArray): String {
        val chars = StringBuilder()
        for (id in ids) {
            if (id >= layout.eot) continue
            if (id in vocab.indices) chars.append(vocab[id])
        }
        val bytes = ArrayList<Byte>(chars.length)
        for (c in chars) {
            val b = UNICODE_TO_BYTE[c]
            if (b != null) {
                bytes.add(b.toByte())
            } else {
                // Not part of the byte alphabet (shouldn't happen for real vocab
                // entries) — pass through as UTF-8 so nothing is silently lost.
                for (raw in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(raw)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        // Multilingual ids kept as named constants: existing call sites and the
        // decoder's default prompt still refer to them.
        const val VOCAB_SIZE = 51_865
        const val EOT = 50_257
        const val SOT = 50_258
        const val LANG_EN = 50_259
        const val TRANSCRIBE = 50_359
        const val NO_TIMESTAMPS = 50_363

        private val cache = HashMap<WhisperLayout, WhisperTokenizer>()

        /** Tokenizer for [layout]; vocabs are loaded once and shared. */
        @Synchronized
        fun of(layout: WhisperLayout): WhisperTokenizer = cache.getOrPut(layout) {
            WhisperTokenizer(layout, loadVocab(layout))
        }

        /** Backwards-compatible multilingual decode. */
        fun decode(ids: IntArray): String = of(WhisperLayout.MULTILINGUAL).decode(ids)

        /** GPT-2 byte<->unicode table: printable ranges map to themselves, the
         *  rest to U+0100.. in order. */
        private val UNICODE_TO_BYTE: Map<Char, Int> by lazy {
            val bs = buildList {
                addAll('!'.code..'~'.code)
                addAll(0xA1..0xAC)
                addAll(0xAE..0xFF)
            }.toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            cs.indices.associate { cs[it].toChar() to bs[it] }
        }

        private fun loadVocab(layout: WhisperLayout): Array<String> {
            val stream = requireNotNull(
                WhisperTokenizer::class.java.classLoader?.getResourceAsStream(layout.resource),
            ) { "Missing bundled whisper vocab: ${layout.resource}" }
            val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
            // The English vocab only holds the text tokens (specials live above
            // it), so the file may be shorter than the model's vocab dimension.
            check(lines.size >= layout.eot) {
                "${layout.resource} has ${lines.size} entries, need at least ${layout.eot}"
            }
            return Array(lines.size) { i ->
                lines[i].replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
            }
        }
    }
}

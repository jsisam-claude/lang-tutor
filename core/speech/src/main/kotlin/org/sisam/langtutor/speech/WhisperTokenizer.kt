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
    /** `<|startofprev|>`: what precedes a text prompt to the decoder. */
    val sotPrev: Int,
    internal val resource: String,
) {
    MULTILINGUAL(
        vocabSize = 51_865,
        eot = 50_257,
        prompt = intArrayOf(50_258, 50_259, 50_359, 50_363),
        sotPrev = 50_361,
        resource = "whisper/vocab.txt",
    ),
    ENGLISH(
        vocabSize = 51_864,
        eot = 50_256,
        prompt = intArrayOf(50_257, 50_362),
        sotPrev = 50_360,
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
 * Whisper tokenizer. Byte-level BPE: token strings from the bundled vocab are
 * sequences of GPT-2 "byte unicode" characters; decoding maps each char back
 * to its raw byte and interprets the result as UTF-8.
 *
 * The ENCODE side exists for one purpose: the drill knows the line it is
 * listening for, and Whisper's decoder takes a text prompt that biases what
 * it writes — "OK" rather than "okay", "pinecones" rather than "pine cones",
 * which on a short item is the difference between a star and "Almost!". No
 * merge table is bundled, so [encode] is a greedy longest match over the
 * vocabulary rather than true BPE. Measured against the reference tokenizer
 * over the whole phrasebank it produces the identical ids for 94% of lines;
 * where it differs (rare compounds) the pieces still spell the same text,
 * which is what the decoder is being shown.
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

    /**
     * Text → token ids, greedy longest match. A word-initial space belongs
     * to the word ("Ġthe"), so a prompt should start with one, as Whisper's
     * own convention does. Bytes no token covers are dropped.
     */
    fun encode(text: String): IntArray {
        val chars = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) chars.append(BYTE_TO_UNICODE[b.toInt() and 0xFF])
        val s = chars.toString()
        val out = ArrayList<Int>(s.length / 3 + 1)
        var i = 0
        while (i < s.length) {
            var matched = false
            var j = minOf(s.length, i + LONGEST_PIECE)
            while (j > i) {
                val id = index[s.substring(i, j)]
                if (id != null) {
                    out.add(id); i = j; matched = true
                    break
                }
                j--
            }
            if (!matched) i++
        }
        return out.toIntArray()
    }

    /** Token string → id, over the content tokens only. Built on first use. */
    private val index: Map<String, Int> by lazy {
        HashMap<String, Int>(layout.eot * 2).also { m ->
            for (id in 0 until minOf(layout.eot, vocab.size)) m.putIfAbsent(vocab[id], id)
        }
    }

    companion object {
        /** Longest vocabulary entry worth trying, in byte-unicode chars. */
        private const val LONGEST_PIECE = 16

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

        /** The same table the other way: raw byte → byte-unicode char. */
        private val BYTE_TO_UNICODE: CharArray by lazy {
            CharArray(256).also { t -> for ((c, b) in UNICODE_TO_BYTE) t[b] = c }
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

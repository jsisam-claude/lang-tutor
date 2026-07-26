package org.sisam.langtutor.speech

/**
 * Whisper multilingual tokenizer — DECODE side only (the ASR loop never needs
 * to encode text). Byte-level BPE: token strings from the bundled vocab are
 * sequences of GPT-2 "byte unicode" characters; decoding maps each char back
 * to its raw byte and interprets the result as UTF-8.
 *
 * Vocab resource: whisper/vocab.txt — one token per line, line number = id
 * (51_865 entries, generated from openai/whisper-medium tokenizer.json; \n \r \\
 * escaped). Golden-tested against HF's WhisperTokenizer in WhisperTokenizerTest.
 */
object WhisperTokenizer {

    const val VOCAB_SIZE = 51_865
    const val EOT = 50_257
    const val SOT = 50_258
    const val LANG_EN = 50_259
    const val TRANSCRIBE = 50_359
    const val NO_TIMESTAMPS = 50_363

    private val vocab: Array<String> by lazy(::loadVocab)

    /** GPT-2 byte<->unicode table: printable ranges map to themselves, the rest
     *  to U+0100.. in order. */
    private val unicodeToByte: Map<Char, Int> by lazy {
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

    /** Decode token ids to text, skipping special/timestamp tokens (>= [EOT]). */
    fun decode(ids: IntArray): String {
        val chars = StringBuilder()
        for (id in ids) {
            if (id >= EOT) continue
            if (id in 0 until VOCAB_SIZE) chars.append(vocab[id])
        }
        val bytes = ArrayList<Byte>(chars.length)
        for (c in chars) {
            val b = unicodeToByte[c]
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

    private fun loadVocab(): Array<String> {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("whisper/vocab.txt")) {
            "Missing bundled whisper vocab"
        }
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        check(lines.size >= VOCAB_SIZE) { "vocab has ${lines.size} entries, expected $VOCAB_SIZE" }
        return Array(VOCAB_SIZE) { i ->
            lines[i].replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
        }
    }
}

package org.sisam.langtutor.speech

import java.text.Normalizer

/**
 * Char-level tokenizer of the dicta/phonikud nikud model (841-entry vocab,
 * pinned to resources by scripts/gen-phonikud-frontend-data.sh). The HF
 * pipeline is NFKC → lowercase → strip combining marks → one token per char,
 * wrapped in [CLS]…[SEP]; for the Hebrew/ASCII text this app feeds it (after
 * [NikudRestorer.removeNikud]) that reduces to a per-char map lookup, which
 * keeps token index i aligned with text index i-1 — the alignment
 * [NikudRestorer.restore] depends on.
 */
class DictaTokenizer private constructor(private val vocab: Map<String, Int>) {

    val clsId = vocab.getValue("[CLS]")
    val sepId = vocab.getValue("[SEP]")
    val padId = vocab.getValue("[PAD]")
    val unkId = vocab.getValue("[UNK]")

    /** Token ids for [text]: [CLS] + one id per char + [SEP]. */
    fun encode(text: String): LongArray {
        val ids = LongArray(text.length + 2)
        ids[0] = clsId.toLong()
        for (i in text.indices) {
            ids[i + 1] = idOf(text[i]).toLong()
        }
        ids[ids.size - 1] = sepId.toLong()
        return ids
    }

    private fun idOf(c: Char): Int {
        vocab[c.toString()]?.let { return it }
        // NFKC + lowercase, mirroring the HF normalizer; anything that maps to
        // more than one char (exotic ligatures) or to a combining mark becomes
        // UNK so the 1:1 char alignment is preserved.
        val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFKC).lowercase()
        if (n.length == 1 && Character.getType(n[0]) != Character.NON_SPACING_MARK.toInt()) {
            vocab[n]?.let { return it }
        }
        return unkId
    }

    companion object {
        fun load(): DictaTokenizer {
            val loader = DictaTokenizer::class.java.classLoader
                ?: error("no classloader for phonikud resources")
            val vocab = HashMap<String, Int>(1024)
            loader.getResourceAsStream("phonikud/tokenizer-vocab.tsv")
                ?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val token = unescape(line.substring(tab + 1))
                        vocab[token] = line.substring(0, tab).toInt()
                    }
                } ?: error("phonikud/tokenizer-vocab.tsv missing from resources")
            check(vocab.size > 800) { "dicta vocab looks truncated: ${vocab.size}" }
            return DictaTokenizer(vocab)
        }

        /** Inverse of the generator's escaping (\\t \\n \\r \\\\). */
        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        else -> sb.append(c).append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i += 1
                }
            }
            return sb.toString()
        }
    }
}

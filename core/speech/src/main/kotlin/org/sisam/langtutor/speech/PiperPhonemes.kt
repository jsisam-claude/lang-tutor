package org.sisam.langtutor.speech

/**
 * Phoneme-string → Piper VITS input ids for the Hebrew voice
 * (phoneme_type "raw": each phoneme CHAR maps through phoneme-map.tsv, no
 * espeak anywhere). Encoding follows Piper's convention — BOS, then every
 * phoneme id interspersed with PAD, then EOS — validated end-to-end against
 * the real voice model in-container before this port was frozen.
 */
object PiperPhonemes {

    private val idMap: Map<Char, Int> by lazy {
        val loader = PiperPhonemes::class.java.classLoader
            ?: error("no classloader for phonikud resources")
        val map = HashMap<Char, Int>(256)
        loader.getResourceAsStream("phonikud/phoneme-map.tsv")
            ?.bufferedReader(Charsets.UTF_8)
            ?.useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    // The phoneme char is AFTER the tab; single-char entries only.
                    if (tab > 0 && line.length == tab + 2) {
                        map[line[tab + 1]] = line.substring(0, tab).toInt()
                    }
                }
            } ?: error("phonikud/phoneme-map.tsv missing from resources")
        check(map.size > 100) { "piper phoneme map looks truncated: ${map.size}" }
        map
    }

    private const val PAD = 0 // '_'
    private const val BOS = 1 // '^'
    private const val EOS = 2 // '$'

    /** Unmapped chars are skipped, matching the reference behavior. */
    fun toIds(phonemes: String): LongArray {
        val ids = ArrayList<Long>(phonemes.length * 2 + 3)
        ids.add(BOS.toLong())
        ids.add(PAD.toLong())
        for (c in phonemes) {
            val id = idMap[c] ?: continue
            ids.add(id.toLong())
            ids.add(PAD.toLong())
        }
        ids.add(EOS.toLong())
        return ids.toLongArray()
    }
}

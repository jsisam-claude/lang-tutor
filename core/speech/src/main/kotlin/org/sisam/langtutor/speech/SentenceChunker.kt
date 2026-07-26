package org.sisam.langtutor.speech

/**
 * Splits tutor replies into speakable sentence chunks (shared by the bundled
 * TTS engines): a chunk ends at . ! ? followed by whitespace/end. Offsets are
 * into the ORIGINAL text so the UI can karaoke-highlight what is being spoken.
 */
object SentenceChunker {

    data class Chunk(val text: String, val start: Int, val end: Int)

    private val ENDERS = setOf('.', '!', '?')

    fun split(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in ENDERS && (i == text.length - 1 || text[i + 1].isWhitespace())) {
                val piece = text.substring(start, i + 1).trim()
                if (piece.isNotEmpty()) chunks.add(Chunk(piece, start, i + 1))
                start = i + 1
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) chunks.add(Chunk(tail, start, text.length))
        return chunks
    }
}

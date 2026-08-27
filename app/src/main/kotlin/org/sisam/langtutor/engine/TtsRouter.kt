package org.sisam.langtutor.engine

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.sisam.langtutor.speech.HebrewText
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Routes each utterance to the right voice. Any Hebrew letter sends the line
 * to the Hebrew engine WHEN ONE EXISTS.
 *
 * When it does not — which is the shipping state since the Phonikud voice was
 * removed over its CC-BY-NC + academic-only license — a Hebrew line must still
 * DO something. Kokoro phonemizes Hebrew to nothing, so naively forwarding
 * produced a tutor that moved its beak with no sound. Instead: the English
 * parts of a mixed line are spoken and the Hebrew parts are dropped (logged),
 * and a line that is entirely Hebrew completes immediately so the turn state
 * machine never hangs waiting for audio that cannot come.
 */
class TtsRouter(
    private val english: TtsEngine,
    private val hebrew: TtsEngine?,
) : TtsEngine {

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> {
        val he = hebrew
        return when {
            he != null && containsHebrew(text) -> he.speak(text, TutorLanguage.HEBREW, speed)
            containsHebrew(text) -> {
                val latin = stripHebrew(text)
                if (latin.any { it.isLetterOrDigit() }) {
                    Log.w(TAG, "no Hebrew voice installed — speaking only the English part of a mixed line")
                    english.speak(latin, language, speed)
                } else {
                    Log.w(TAG, "no Hebrew voice installed — skipping an all-Hebrew line (${text.length} chars)")
                    flow {
                        emit(TtsEvent.Started)
                        emit(TtsEvent.Completed)
                    }
                }
            }
            else -> english.speak(text, language, speed)
        }
    }

    /**
     * Streaming, in two shapes.
     *
     * **No Hebrew voice installed** — the shipping default — keeps the original
     * fast path exactly: everything goes to the English voice with Hebrew runs
     * stripped per chunk, so the first sentence starts speaking while the model
     * is still decoding the rest. That latency win is the reason streaming
     * exists and is not worth trading away for a voice that is not there.
     * Stripping an ALL-Hebrew sentence leaves nothing, and forwarding that
     * empty string made the voice synthesize silence and consume the sentence
     * slot — so blank chunks are dropped.
     *
     * **Hebrew voice installed** routes each chunk to the voice that can
     * actually say it. The old objection — that per-chunk routing across two
     * engines would interleave two audio sessions — does not apply: chunks are
     * collected sequentially and each [TtsEngine.speak] drains its player
     * before returning, so the two voices take turns rather than overlap. The
     * cost is losing cross-sentence prosody grouping on a bilingual reply,
     * which is a smaller loss than not saying the Hebrew at all.
     */
    override fun speakStream(chunks: Flow<String>, language: TutorLanguage, speed: Float): Flow<TtsEvent> {
        val he = hebrew ?: return english.speakStream(
            chunks
                .map { if (containsHebrew(it)) stripHebrew(it) else it }
                .filter { it.any(Char::isLetterOrDigit) },
            language,
            speed,
        )
        return flow {
            emit(TtsEvent.Started)
            chunks.collect { chunk ->
                if (!chunk.any(Char::isLetterOrDigit)) return@collect
                val voice = if (containsHebrew(chunk)) he else english
                val lang = if (containsHebrew(chunk)) TutorLanguage.HEBREW else language
                // Started/Completed are the STREAM's, not each chunk's — a
                // listener that keys "is it talking" on them must not see the
                // conversation end between two sentences of one reply.
                voice.speak(chunk, lang, speed).collect { event ->
                    if (event !is TtsEvent.Started && event !is TtsEvent.Completed) emit(event)
                }
            }
            emit(TtsEvent.Completed)
        }
    }

    override suspend fun stop() {
        english.stop()
        hebrew?.stop()
    }

    private companion object {
        const val TAG = "TukiTts"

        // One shared definition of "is this Hebrew" (core/speech), so the
        // voice router, the tutor's Hebrew-help trigger and the transcript's
        // text direction can never disagree.
        fun containsHebrew(s: String) = HebrewText.contains(s)
        fun stripHebrew(s: String) = HebrewText.strip(s)
    }
}

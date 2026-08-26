package org.sisam.langtutor.engine

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
     * Streaming goes to the ENGLISH voice only, with Hebrew stripped per chunk:
     * streamed replies come from the LLM mid-generation, where per-chunk
     * language routing across two engines would interleave two audio sessions.
     * Scripted Hebrew lines use [speak], which routes properly.
     */
    override fun speakStream(chunks: Flow<String>, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        english.speakStream(chunks.map { if (containsHebrew(it)) stripHebrew(it) else it }, language, speed)

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

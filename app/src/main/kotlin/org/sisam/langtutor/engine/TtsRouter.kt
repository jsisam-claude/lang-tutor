package org.sisam.langtutor.engine

import kotlinx.coroutines.flow.Flow
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Routes each utterance to the right bundled voice BY SCRIPT, not by the
 * caller's language tag: the orchestrator always requests ENGLISH, but the
 * tutor deliberately drops Hebrew lines for the youngest learners (and the
 * E4B model may scaffold in Hebrew) — those must come out of the Hebrew
 * voice, not be mangled letter-by-letter through an English one. Utterances
 * containing ANY Hebrew letter go to [hebrew] when installed; everything
 * else goes to [english].
 */
class TtsRouter(
    private val english: TtsEngine,
    private val hebrew: TtsEngine?,
) : TtsEngine {

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> {
        val target = if (hebrew != null && text.any { it in 'א'..'ת' }) {
            hebrew to TutorLanguage.HEBREW
        } else {
            english to language
        }
        return target.first.speak(text, target.second, speed)
    }

    override suspend fun stop() {
        english.stop()
        hebrew?.stop()
    }
}

package org.sisam.langtutor.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * DEV SHIM: Android's platform TextToSpeech, so the tutor audibly speaks on a
 * device before the bundled voices (Piper/Kokoro EN, Phonikud HE) land. Hebrew
 * depends on the device's installed voices — another reason the production
 * engine bundles its own.
 */
class PlatformTtsEngine(context: Context) : TtsEngine {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        callbackFlow {
            if (!ready.await()) {
                trySend(TtsEvent.Started)
                trySend(TtsEvent.Completed)
                close()
                return@callbackFlow
            }

            tts.language = when (language) {
                TutorLanguage.ENGLISH -> Locale.US
                TutorLanguage.HEBREW -> Locale("he")
            }
            tts.setSpeechRate(speed)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    trySend(TtsEvent.Started)
                }

                override fun onDone(utteranceId: String?) {
                    trySend(TtsEvent.Completed)
                    close()
                }

                @Deprecated("Deprecated in platform API")
                override fun onError(utteranceId: String?) {
                    trySend(TtsEvent.Completed)
                    close()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    trySend(TtsEvent.Completed)
                    close()
                }
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tuki-${text.hashCode()}")
            awaitClose { }
        }

    override suspend fun stop() {
        tts.stop()
    }
}

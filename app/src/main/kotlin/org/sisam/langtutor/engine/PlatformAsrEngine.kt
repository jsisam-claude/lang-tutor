package org.sisam.langtutor.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.AsrResult
import org.sisam.langtutor.speech.RecognitionHint

/**
 * DEV SHIM: Android's speech recognizer, so the speech loop is real on a phone
 * before the bundled ASR model lands. The bundled Whisper engine replaces this
 * behind the same interface; constrained-vocab hints are ignored here because
 * the platform API has no biasing support — exactly why the bundled engine
 * exists.
 *
 * PRIVACY: where the audio goes is decided by the DEVICE, not by us.
 * [SpeechRecognizer.isOnDeviceRecognitionAvailable] only reports the system's
 * designated on-device recognizer (Google's, in practice). When that is absent
 * we fall back to the system default recognition service, which may be
 * on-device — a de-googled phone commonly has one, e.g. FUTO Voice Input
 * registers a Whisper-backed `android.speech.RecognitionService` — or may be a
 * cloud service. The API gives no way to tell the two apart, so we set
 * [RecognizerIntent.EXTRA_PREFER_OFFLINE], the documented request to keep
 * processing local. It is a request, not a guarantee: for a children's app the
 * only real guarantee is the bundled engine, which is why this stays a
 * fallback and the Parent Zone nags for the model.
 */
class PlatformAsrEngine(private val context: Context) : AsrEngine {

    private var recognizer: SpeechRecognizer? = null
    private var pending: CompletableDeferred<AsrResult>? = null

    override suspend fun startCapture(hint: RecognitionHint) = withContext(Dispatchers.Main) {
        cancelPending()
        val deferred = CompletableDeferred<AsrResult>()
        pending = deferred

        val speechRecognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = speechRecognizer

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                deferred.complete(
                    AsrResult(
                        transcript = texts?.firstOrNull().orEmpty(),
                        confidence = confidences?.firstOrNull() ?: DEFAULT_CONFIDENCE,
                    ),
                )
            }

            override fun onError(error: Int) {
                deferred.complete(AsrResult(transcript = "", confidence = 0f))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Ask whatever service is bound to keep the audio on the device.
            // Honoured by offline recognizers; ignored by cloud ones (see the
            // privacy note in this class's KDoc).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer.startListening(intent)
    }

    override suspend fun stopCapture(): AsrResult {
        val deferred = pending ?: return AsrResult(transcript = "", confidence = 0f)
        withContext(Dispatchers.Main) { recognizer?.stopListening() }
        val result = deferred.await()
        withContext(Dispatchers.Main) {
            recognizer?.destroy()
            recognizer = null
        }
        pending = null
        return result
    }

    private fun cancelPending() {
        pending?.complete(AsrResult(transcript = "", confidence = 0f))
        pending = null
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.8f
    }
}

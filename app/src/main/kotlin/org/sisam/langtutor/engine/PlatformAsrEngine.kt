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
 * DEV SHIM: Android's on-device speech recognizer (API 31+), so the speech loop
 * is real on a phone before the bundled ASR models land. The production engine
 * (whisper.cpp/sherpa-onnx from the model pack) replaces this behind the same
 * interface; constrained-vocab hints are ignored here because the platform API
 * has no biasing support — exactly why the bundled engine exists.
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

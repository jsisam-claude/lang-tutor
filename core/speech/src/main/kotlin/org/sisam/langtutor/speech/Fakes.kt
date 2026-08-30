package org.sisam.langtutor.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Returns queued results (or a default) so app and tests can run full turns
 * without a microphone. Records capture calls and hints for assertions.
 */
class FakeAsrEngine : AsrEngine {

    val recordedHints = mutableListOf<RecognitionHint>()
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    private val queue = ArrayDeque<AsrResult>()

    fun enqueue(result: AsrResult) {
        queue.addLast(result)
    }

    override suspend fun startCapture(hint: RecognitionHint) {
        startCalls++
        recordedHints += hint
    }

    override suspend fun stopCapture(): AsrResult {
        stopCalls++
        return queue.removeFirstOrNull() ?: DEFAULT_RESULT
    }

    private val _speculative = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val speculative: Flow<String> get() = _speculative

    /** Tests drive the soft-endpoint guesses by hand. */
    suspend fun emitSpeculative(guess: String) {
        _speculative.emit(guess)
    }

    companion object {
        val DEFAULT_RESULT = AsrResult(
            transcript = "I see a red ball",
            confidence = 0.92f,
            audio = AudioClip(ShortArray(16_000)),
        )
    }
}

/** Emits Started → Completed with a short delay; plays no audio. */
class FakeTtsEngine : TtsEngine {

    data class Utterance(val text: String, val language: TutorLanguage, val speed: Float)

    val spoken = mutableListOf<Utterance>()

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        spoken += Utterance(text, language, speed)
        emit(TtsEvent.Started)
        delay(SPEAK_DELAY_MS)
        emit(TtsEvent.Completed)
    }

    override suspend fun stop() = Unit

    companion object {
        private const val SPEAK_DELAY_MS = 30L
    }
}

/** Deterministic per-letter scores so UI and tests have stable data. */
class FakePronunciationScorer : PronunciationScorer {

    override suspend fun score(
        audio: AudioClip,
        expectedText: String,
        language: TutorLanguage,
    ): PronunciationScore {
        val letters = expectedText.filter { it.isLetter() }.lowercase()
        val phonemes = letters.mapIndexed { index, ch ->
            val score = if (index % 3 == 2) 0.6f else 0.9f
            PhonemeScore(symbol = ch.toString(), score = score)
        }
        val overall = if (phonemes.isEmpty()) 0f else phonemes.map { it.score }.average().toFloat()
        return PronunciationScore(overall = overall, phonemes = phonemes)
    }
}

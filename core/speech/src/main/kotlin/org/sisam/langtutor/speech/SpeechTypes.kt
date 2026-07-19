package org.sisam.langtutor.speech

/** PCM mono audio. 16 kHz is the native rate of the ASR/scoring models. */
class AudioClip(
    val samples: ShortArray,
    val sampleRateHz: Int = 16_000,
) {
    val durationMs: Long
        get() = samples.size * 1000L / sampleRateHz
}

enum class TutorLanguage { ENGLISH, HEBREW }

sealed interface RecognitionHint {
    data object None : RecognitionHint

    /**
     * Bias recognition toward the lesson's expected words/phrases (sherpa-onnx
     * hotwords / Vosk grammar in production). This is the main lever against the
     * children's-speech WER penalty in structured activities.
     */
    data class ConstrainedVocab(val phrases: List<String>) : RecognitionHint
}

data class AsrResult(
    val transcript: String,
    val confidence: Float,
    /** Retained for the turn only, so the orchestrator can score pronunciation. */
    val audio: AudioClip? = null,
)

sealed interface TtsEvent {
    data object Started : TtsEvent

    /** Character range of the text currently being spoken (for karaoke highlight). */
    data class RangeSpoken(val start: Int, val end: Int) : TtsEvent
    data object Completed : TtsEvent
}

data class PhonemeScore(val symbol: String, val score: Float)

data class PronunciationScore(
    val overall: Float,
    val phonemes: List<PhonemeScore>,
)

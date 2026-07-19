package org.sisam.langtutor.speech

import kotlinx.coroutines.flow.Flow

/**
 * Push-to-talk speech recognition. Production: whisper.cpp/Moonshine or
 * sherpa-onnx (streaming, P3) — all fully on-device.
 */
interface AsrEngine {
    suspend fun startCapture(hint: RecognitionHint = RecognitionHint.None)

    /** Stops the mic and returns the final result for the captured utterance. */
    suspend fun stopCapture(): AsrResult
}

/**
 * Text-to-speech. Production: Piper/Kokoro for [TutorLanguage.ENGLISH],
 * Phonikud + Piper-class voice for [TutorLanguage.HEBREW]; fixed Hebrew
 * instruction lines ship as pre-recorded human audio and bypass TTS entirely.
 * [speed] < 1.0 is the child-friendly slow-clear mode.
 */
interface TtsEngine {
    fun speak(text: String, language: TutorLanguage, speed: Float = 1.0f): Flow<TtsEvent>
    suspend fun stop()
}

/**
 * Per-phoneme pronunciation scoring (CTC-GOP in production, P3). No offline SDK
 * exists anywhere for this — see docs/feasibility.md §5 — so this interface is
 * where the in-house scorer lands.
 */
interface PronunciationScorer {
    suspend fun score(
        audio: AudioClip,
        expectedText: String,
        language: TutorLanguage = TutorLanguage.ENGLISH,
    ): PronunciationScore
}

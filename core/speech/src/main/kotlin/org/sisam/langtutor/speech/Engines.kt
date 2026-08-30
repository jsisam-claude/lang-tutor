package org.sisam.langtutor.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Push-to-talk speech recognition. Production: whisper.cpp/Moonshine or
 * sherpa-onnx (streaming, P3) — all fully on-device.
 */
interface AsrEngine {
    suspend fun startCapture(hint: RecognitionHint = RecognitionHint.None)

    /** Stops the mic and returns the final result for the captured utterance. */
    suspend fun stopCapture(): AsrResult

    /**
     * True when the engine can decide by itself that the child finished
     * speaking (bundled VAD). Push-to-talk engines leave this false and the UI
     * keeps its hold-to-talk button.
     */
    val supportsHandsFree: Boolean get() = false

    /**
     * Load whatever the first capture would otherwise load, so the first mic
     * press does not pay a model load inside the turn. Default no-op: the
     * platform recognizer has nothing to warm.
     */
    fun warmUp() {}

    /**
     * Hands-free turn-taking: suspends until end-of-speech is detected, so a
     * young child never has to hold a button. Only meaningful between
     * [startCapture] and [stopCapture], and only when [supportsHandsFree].
     */
    suspend fun awaitEndpoint() = Unit
}

/**
 * Text-to-speech. [TutorLanguage.ENGLISH] is REAL today: the bundled Kokoro
 * voice (KokoroTtsEngine in :app, fed by [KokoroPhonemizer]). Planned:
 * Phonikud + Piper-class voice for [TutorLanguage.HEBREW]; fixed Hebrew
 * instruction lines ship as pre-recorded human audio and bypass TTS entirely.
 * [speed] < 1.0 is the child-friendly slow-clear mode.
 */
interface TtsEngine {
    fun speak(text: String, language: TutorLanguage, speed: Float = 1.0f): Flow<TtsEvent>

    /**
     * Speak sentence-sized [chunks] AS THEY ARRIVE — the streaming half of the
     * voice loop. The orchestrator feeds this from the LLM token stream, so the
     * first sentence is audible while the rest of the reply is still decoding;
     * on a CPU-decode phone that removes most of the dead air between the child
     * finishing and Tuki starting (a 96-token reply at phone-CPU rates is many
     * seconds of silence otherwise).
     *
     * Default: collect everything, then [speak] once — correct for engines with
     * no incremental path (platform TTS, fakes), and exactly the pre-streaming
     * behaviour. Engines that can start early override it.
     */
    fun speakStream(chunks: Flow<String>, language: TutorLanguage, speed: Float = 1.0f): Flow<TtsEvent> = flow {
        val all = StringBuilder()
        chunks.collect { chunk ->
            if (all.isNotEmpty()) all.append(' ')
            all.append(chunk.trim())
        }
        if (all.isNotEmpty()) emitAll(speak(all.toString(), language, speed))
    }

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

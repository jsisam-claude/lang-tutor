package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.sisam.langtutor.speech.KokoroPhonemizer
import org.sisam.langtutor.speech.SentenceChunker
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TukiVoices
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Bundled Kokoro-82M voice — Tuki speaks with OUR OWN on-device TTS, no Google
 * services and no system voices needed (GrapheneOS has neither). Single-graph
 * ONNX build (StyleTTS2 family): phoneme ids + style vector + speed in, 24 kHz
 * waveform out. The int8/fp32-compute export is deliberate: the smaller
 * fp16-activation build (q8f16) returns an all-NaN waveform on ARM, though it
 * is clean on x86 — see the non-finite guard in [synthesize]; [KokoroPhonemizer] provides the ids, the af_heart voice ships
 * in APK assets (fetched + SHA-256-pinned by scripts/fetch-voice-assets.sh),
 * and the 86 MB model installs like the LLM (Parent Zone pack / import).
 *
 * [AppContainer] holds ONE instance app-wide: the ORT session mmaps the model
 * once and is reused across sessions (it is stateless per call). Playback and
 * sentence chunking are shared with the Hebrew voice ([PcmPlayer],
 * [SentenceChunker]).
 *
 * DEVICE-VERIFY (docs/bench.md): per-sentence synth RTF on Tensor CPU — logcat
 * tag [TAG] prints ms per chunk vs seconds of audio.
 */
class KokoroTtsEngine(context: Context, private val modelFile: File) : TtsEngine {

    private val appContext = context.applicationContext
    private val phonemizer by lazy { KokoroPhonemizer.load() }
    private val player = PcmPlayer(SAMPLE_RATE)

    /**
     * Which conditioning table to load. Settable because switching voices is
     * just loading a different 510x256 table — no model reload — so a parent
     * changing it in the picker takes effect on the next sentence.
     */
    @Volatile
    var voiceAsset: String = TukiVoices.DEFAULT_ID
        set(value) {
            if (field != value) {
                field = value
                synchronized(voiceLock) { loadedVoice = null }
                Log.i(TAG, "voice switched to $value")
            }
        }

    private val voiceLock = Any()
    @Volatile private var loadedVoice: FloatArray? = null

    /** 510 rows × 256 floats; row (tokens+2-1) conditions the voice, upstream convention. */
    private val voice: FloatArray
        get() = loadedVoice ?: synchronized(voiceLock) {
            loadedVoice ?: readVoice(voiceAsset).also { loadedVoice = it }
        }

    private fun readVoice(asset: String): FloatArray =
        appContext.assets.open("$VOICE_DIR/$asset").use { input ->
            val bytes = input.readBytes()
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            floats
        }

    // Nullable + accessor rather than `by lazy`, so trimMemory() can close the
    // session and the next line of speech quietly reloads it.
    @Volatile private var session: OrtSession? = null

    private fun session(): OrtSession = session ?: synchronized(this) {
        session ?: createSession().also { session = it }
    }

    /** Frees the ORT session under memory pressure; next use reloads lazily. */
    fun release() = synchronized(this) {
        session?.let {
            runCatching { it.close() }
            session = null
            Log.i(TAG, "session released (memory pressure)")
        }
    }

    private fun createSession(): OrtSession =
        // step(), not begin()/end(): a session that fails to create must still
        // clear the status, or the UI spins on a step that is already over.
        EngineStatus.step(EngineStatus.Kind.TTS_LOAD, modelFile.name) {
            val started = System.nanoTime()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                // BASIC, not ALL: ORT's extended optimizer crashed on this graph in
                // testing (desktop 1.28); basic fusions are enough for realtime.
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, opts).also {
                Log.i(TAG, "kokoro session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        // Kokoro is an English voice; Hebrew letters phonemize to nothing and the
        // chunk is skipped, so a stray Hebrew line degrades to silence, not a crash.
        for (chunk in SentenceChunker.split(text)) {
            if (player.interrupted) break
            val ids = phonemizer.phonemize(chunk.text)
            if (ids.isEmpty()) continue
            emit(TtsEvent.RangeSpoken(chunk.start, chunk.end))
            val audio = EngineStatus.step(EngineStatus.Kind.TTS_RUN, "${ids.size} phonemes") {
                synthesize(ids, speed)
            }
            if (audio.isNotEmpty() && !player.interrupted) player.play(audio)
        }
        player.release()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming path: each incoming chunk is already a sentence (the
     * orchestrator runs SentenceChunker on the LLM token stream), so synthesis
     * starts on the FIRST sentence while later ones are still being generated.
     * Playback order is preserved because collection is sequential.
     */
    override fun speakStream(chunks: Flow<String>, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        chunks.collect { sentence ->
            if (player.interrupted) return@collect
            val ids = phonemizer.phonemize(sentence)
            if (ids.isEmpty()) return@collect
            val audio = EngineStatus.step(EngineStatus.Kind.TTS_RUN, "${ids.size} phonemes (stream)") {
                synthesize(ids, speed)
            }
            if (audio.isNotEmpty() && !player.interrupted) player.play(audio)
        }
        player.release()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        player.interrupted = true
        player.release()
    }

    /** Force the lazy pieces now (background call) so first speak is instant. */
    fun warmUp() {
        session()
        voice
        phonemizer
    }

    // @Synchronized so release() waits for the in-flight chunk instead of
    // closing the session underneath it.
    @Synchronized
    private fun synthesize(ids: IntArray, speed: Float): FloatArray {
        val started = System.nanoTime()
        val tokens = LongArray(ids.size + 2) // BOS=0 … EOS=0 (StyleTTS2 convention)
        for (i in ids.indices) tokens[i + 1] = ids[i].toLong()
        val rows = voice.size / STYLE_DIM
        val row = (tokens.size - 1).coerceIn(0, rows - 1)
        val style = voice.copyOfRange(row * STYLE_DIM, (row + 1) * STYLE_DIM)

        val env = OrtEnvironment.getEnvironment()
        val inputs = mapOf(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())),
            "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong())),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)),
        )
        try {
            session().run(inputs).use { results ->
                val buf = (results[0] as OnnxTensor).floatBuffer
                val audio = FloatArray(buf.remaining())
                buf.get(audio)
                val ms = (System.nanoTime() - started) / 1_000_000
                // Shape of the waveform, not just its length: a device that
                // reports plausible duration but garbage samples is an ONNX
                // problem, while sane samples that SOUND wrong is a playback
                // problem. Reference values from the same model+ids on x86:
                // peak 0.45, rms 0.065, zero-crossing 0.11. White noise sits
                // near zcr 0.5, and silence at peak 0.
                var peak = 0f
                var sumSq = 0.0
                var crossings = 0
                var nonFinite = 0
                for (i in audio.indices) {
                    val v = audio[i]
                    if (!v.isFinite()) { nonFinite++; continue }
                    if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                    sumSq += v.toDouble() * v
                    if (i > 0 && audio[i - 1] * v < 0f) crossings++
                }
                val rms = kotlin.math.sqrt(sumSq / audio.size.coerceAtLeast(1))
                val zcr = crossings.toFloat() / audio.size.coerceAtLeast(1)
                Log.i(
                    TAG,
                    "synth ${ids.size} tokens -> " +
                        "${"%.2f".format(audio.size / SAMPLE_RATE.toFloat())}s in ${ms}ms " +
                        "peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} " +
                        "zcr=${"%.3f".format(zcr)} (ref peak~0.46 rms~0.064 zcr~0.23)",
                )
                // NEVER hand non-finite samples to AudioTrack: it renders
                // them as a burst of noise at full volume, which is what a
                // Pixel reported when the fp16-activation export (q8f16)
                // produced an all-NaN waveform on ARM while the same file was
                // clean on x86. Silence plus a loud log beats hurting a
                // child's ears, and the count names the real fault.
                if (nonFinite > 0) {
                    Log.e(
                        TAG,
                        "discarding waveform: $nonFinite/${audio.size} samples are NaN/Inf — " +
                            "the ONNX export is producing garbage on this device, not the audio path",
                    )
                    return FloatArray(0)
                }
                return audio
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val TAG = "TukiTts"
        private const val SAMPLE_RATE = 24_000
        private const val STYLE_DIM = 256
        private const val THREADS = 4

        /** All English voices from onnx-community/Kokoro-82M-v1.0-ONNX live
         *  here, pinned by scripts/fetch-voice-assets.sh. */
        const val VOICE_DIR = "kokoro"
    }
}

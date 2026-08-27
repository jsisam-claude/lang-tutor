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
import org.sisam.langtutor.speech.KokoroFrontEnd
import org.sisam.langtutor.speech.ParrotEffect
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
class KokoroTtsEngine(
    context: Context,
    private val modelFile: File,
    /**
     * Text → phoneme ids. Defaults to the English G2P; the Hebrew voice is the
     * same Kokoro architecture over a byte-identical vocabulary, so it is the
     * SAME engine with [HebrewPhonemes] here instead.
     */
    frontEnd: Lazy<KokoroFrontEnd> = lazy { KokoroPhonemizer.load() },
    /** Where the 510x256 conditioning tables live. */
    private val voices: VoiceStore = VoiceStore.Assets(context, VOICE_DIR),
    /** Fallback when the requested voice is not in this build. */
    private val defaultVoice: String = TukiVoices.DEFAULT_ID,
    private val tag: String = TAG,
) : TtsEngine {

    private val phonemizer by frontEnd
    private val player = PcmPlayer(SAMPLE_RATE)

    /**
     * Which conditioning table to load. Settable because switching voices is
     * just loading a different 510x256 table — no model reload — so a parent
     * changing it in the picker takes effect on the next sentence.
     */
    @Volatile
    var voiceAsset: String = defaultVoice
        set(value) {
            if (field != value) {
                field = value
                synchronized(voiceLock) { loadedVoice = null }
                Log.i(tag, "voice switched to $value")
            }
        }

    private val voiceLock = Any()
    @Volatile private var loadedVoice: FloatArray? = null

    /** 510 rows × 256 floats; row (tokens+2-1) conditions the voice, upstream convention. */
    private val voice: FloatArray
        get() = loadedVoice ?: synchronized(voiceLock) {
            loadedVoice ?: readVoice(voiceAsset).also { loadedVoice = it }
        }

    private fun readVoice(asset: String): FloatArray = try {
        readTable(asset)
    } catch (e: java.io.FileNotFoundException) {
        // A persisted preference can name a voice THIS build does not carry —
        // the classic case is a local build made without re-running
        // scripts/fetch-voice-assets.sh, whose assets/kokoro/ still holds only
        // the old default. One bad tap in the picker must not take speech down
        // with it (it used to: every synthesis threw, every turn failed, and
        // the broken choice persisted across restarts).
        Log.e(tag, "voice '$asset' is not in this build — falling back to $defaultVoice", e)
        readTable(defaultVoice)
    }

    private fun readTable(asset: String): FloatArray {
        val bytes = voices.read(asset)
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
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
            Log.i(tag, "session released (memory pressure)")
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
                Log.i(tag, "kokoro session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        speakInternal(text, speed, flavored = false)

    /**
     * The same voice wearing the parrot: personality lines only, reached
     * through [ParrotVoice]. Teaching speech ([speak], [speakStream]) never
     * passes through the effect — see [org.sisam.langtutor.speech.ParrotEffect]
     * for the doctrine.
     */
    fun speakFlavored(text: String, speed: Float): Flow<TtsEvent> =
        speakInternal(text, speed, flavored = true)

    private fun speakInternal(text: String, speed: Float, flavored: Boolean): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        var first = true
        // A chunk the front end cannot phonemize yields no ids and is skipped,
        // so text in the wrong script degrades to silence rather than a crash.
        //
        // GROUPED, not per-sentence: synthesizing each sentence in isolation
        // gave every one the same isolated-statement contour — and because the
        // style row is indexed by token count, short sentences also kept
        // hitting the same narrow band of the voice table. Both read as
        // monotone. Grouping restores cross-sentence intonation and moves the
        // input into richer style rows.
        for (group in groupForProsody(SentenceChunker.split(text))) {
            if (player.interrupted) break
            val ids = phonemizer.phonemize(group.text)
            if (ids.isEmpty()) continue
            emit(TtsEvent.RangeSpoken(group.start, group.end))
            // Flavored lines synthesize SLOWER by the pitch factor, so the
            // resample inside the effect lands the duration back where it was
            // asked for while pitch and formants ride up together.
            val synthSpeed = if (flavored) vary(speed) / ParrotEffect.PITCH else vary(speed)
            var audio = EngineStatus.step(EngineStatus.Kind.TTS_RUN, "${ids.size} phonemes") {
                synthesize(ids, synthSpeed)
            }
            if (flavored && audio.isNotEmpty()) {
                audio = ParrotEffect.apply(audio, SAMPLE_RATE)
                if (first && !player.interrupted) {
                    // The "brrp!" announces the character before the words.
                    player.play(ParrotEffect.flourish(SAMPLE_RATE))
                }
            }
            first = false
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
        // First sentence alone — it IS the latency win streaming exists for.
        // After that, sentences are paired before synthesis so the contour
        // spans sentence boundaries (see the prosody note in speak()). The
        // LLM decodes several times faster than speech plays, so by the time
        // a pair is spoken the next pair has long since arrived — the pairing
        // costs no audible gap.
        var first = true
        val pending = StringBuilder()
        var pendingCount = 0
        suspend fun synthAndPlay(text: String, label: String) {
            val ids = phonemizer.phonemize(text)
            if (ids.isEmpty()) return
            val audio = EngineStatus.step(EngineStatus.Kind.TTS_RUN, "${ids.size} phonemes ($label)") {
                synthesize(ids, vary(speed))
            }
            if (audio.isNotEmpty() && !player.interrupted) player.play(audio)
        }
        chunks.collect { sentence ->
            if (player.interrupted) return@collect
            if (first) {
                first = false
                synthAndPlay(sentence, "stream")
            } else {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(sentence)
                pendingCount++
                if (pendingCount >= GROUP_MAX_SENTENCES || pending.length >= GROUP_TARGET_CHARS) {
                    synthAndPlay(pending.toString(), "stream pair")
                    pending.setLength(0)
                    pendingCount = 0
                }
            }
        }
        if (pending.isNotEmpty() && !player.interrupted) synthAndPlay(pending.toString(), "stream tail")
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
                    tag,
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

    private data class ProsodyGroup(val text: String, val start: Int, val end: Int)

    /** Merge adjacent sentence chunks up to the group limits, keeping original
     *  offsets so karaoke highlighting still tracks the source text. */
    private fun groupForProsody(chunks: List<SentenceChunker.Chunk>): List<ProsodyGroup> {
        val out = ArrayList<ProsodyGroup>()
        var text = StringBuilder()
        var start = -1
        var end = -1
        var count = 0
        fun flush() {
            if (text.isNotEmpty()) out.add(ProsodyGroup(text.toString(), start, end))
            text = StringBuilder(); start = -1; count = 0
        }
        for (c in chunks) {
            if (start < 0) start = c.start
            if (text.isNotEmpty()) text.append(' ')
            text.append(c.text)
            end = c.end
            count++
            if (count >= GROUP_MAX_SENTENCES || text.length >= GROUP_TARGET_CHARS) flush()
        }
        flush()
        return out
    }

    /** Small per-group speed variation (±2%): identical pace on every chunk is
     *  part of what read as robotic. Inaudible as a tempo change, audible as
     *  life. */
    private fun vary(base: Float): Float =
        base * (0.98f + kotlin.random.Random.nextFloat() * 0.04f)

    companion object {
        private const val TAG = "TukiTts"

        /** Prosody grouping: enough for a contour to span a boundary, small
         *  enough that synthesis latency stays in per-reply territory. */
        private const val GROUP_MAX_SENTENCES = 2
        private const val GROUP_TARGET_CHARS = 160
        /** Every Kokoro export in this family is 24 kHz, Hebrew included. */
        private const val SAMPLE_RATE = 24_000
        private const val STYLE_DIM = 256
        private const val THREADS = 4

        /** All English voices from onnx-community/Kokoro-82M-v1.0-ONNX live
         *  here, pinned by scripts/fetch-voice-assets.sh. */
        const val VOICE_DIR = "kokoro"

        /** Logcat tag for the Hebrew instance, so two voices are tellable apart. */
        const val TAG_HEBREW = "TukiTtsHe"
    }
}

/**
 * A [TtsEngine] view of the SAME Kokoro engine that speaks with the parrot
 * flavor — same ORT session, same player, same picked voice; only the
 * waveform treatment differs. Handed to orchestrators as their personality
 * voice, so core code stays interface-only and cannot accidentally route a
 * teaching line through the effect.
 */
class ParrotVoice(private val engine: KokoroTtsEngine) : TtsEngine {

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        engine.speakFlavored(text, speed)

    override suspend fun stop() = engine.stop()
}

/**
 * Where a Kokoro conditioning table comes from.
 *
 * The English voices ride in APK assets (522 KB each, 28 of them); the Hebrew
 * one arrives with its model as an installed pack, because its weights cannot
 * be bundled. Same 510x256 float table either way.
 */
sealed interface VoiceStore {

    /** @throws java.io.FileNotFoundException when this build has no such voice. */
    fun read(name: String): ByteArray

    class Assets(context: Context, private val dir: String) : VoiceStore {
        private val appContext = context.applicationContext
        override fun read(name: String): ByteArray =
            appContext.assets.open("$dir/$name").use { it.readBytes() }
    }

    class Files(private val dir: File) : VoiceStore {
        override fun read(name: String): ByteArray {
            val f = File(dir, name)
            if (!f.exists()) throw java.io.FileNotFoundException(f.absolutePath)
            return f.readBytes()
        }
    }
}

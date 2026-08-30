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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.sisam.langtutor.speech.KaraokeTiming
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
    /** Identifies this install, so an app update retries XNNPACK once. */
    private val installStamp: String = "",
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
            // The cached audio is worth far less than the model beside it.
            SynthCache.clear()
            Log.i(tag, "session released (memory pressure)")
        }
    }

    private fun createSession(): OrtSession =
        // step(), not begin()/end(): a session that fails to create must still
        // clear the status, or the UI spins on a step that is already over.
        EngineStatus.step(EngineStatus.Kind.TTS_LOAD, modelFile.name) {
            val started = System.nanoTime()
            // Provider and thread budget live in OnnxTuning — see there for
            // why four threads was the wrong number on a big.LITTLE phone.
            OnnxTuning.createSession(modelFile.absolutePath, tag, installStamp).also {
                Log.i(tag, "kokoro session loaded in ${(System.nanoTime() - started) / 1_000_000}ms")
            }
        }

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        speakInternal(text, speed, flavorPitch = null)

    /**
     * The same voice wearing the parrot: personality lines only, reached
     * through [ParrotVoice], or directly when a caller wants its own register
     * via [pitch]. Teaching speech ([speak], [speakStream]) never passes
     * through the effect — see [org.sisam.langtutor.speech.ParrotEffect] for
     * the doctrine.
     */
    fun speakFlavored(text: String, speed: Float, pitch: Float = ParrotEffect.PITCH): Flow<TtsEvent> =
        speakInternal(text, speed, flavorPitch = pitch)

    private fun speakInternal(text: String, speed: Float, flavorPitch: Float?): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        // SYNTH-AHEAD (docs/latency.md item 1): the player deliberately drains —
        // "done" must mean finished SOUNDING, because the mic opens next — so
        // running synthesis and playback in one loop made every sentence gap
        // exactly one synthesis long (7.5 s once, measured, on a throttled
        // phone). A rendezvous handoff overlaps them instead: while group N is
        // sounding, group N+1 is synthesizing. Gaps vanish at RTF <= 1 and
        // halve when throttled, and memory holds at most one rendered group
        // beyond the one playing.
        coroutineScope {
            val rendered = Channel<Rendered>(Channel.RENDEZVOUS)
            val producer = launch(Dispatchers.IO) {
                try {
                    // A chunk the front end cannot phonemize yields no ids and
                    // is skipped, so text in the wrong script degrades to
                    // silence rather than a crash.
                    //
                    // GROUPED, not per-sentence: synthesizing each sentence in
                    // isolation gave every one the same isolated-statement
                    // contour — and because the style row is indexed by token
                    // count, short sentences also kept hitting the same narrow
                    // band of the voice table. Both read as monotone. Grouping
                    // restores cross-sentence intonation and moves the input
                    // into richer style rows.
                    for (group in groupForProsody(SentenceChunker.split(text))) {
                        if (player.interrupted) break
                        val audio = renderOrCache(group.text, speed, flavorPitch, "speak")
                        if (audio.isEmpty()) continue
                        // Word timing for karaoke: each word's share of the
                        // waveform, weighted by its phoneme count — cheap
                        // dictionary lookups, so cached audio gets timed too.
                        // Flavored (personality) lines are not karaoke text.
                        val timing = if (flavorPitch == null) {
                            KaraokeTiming.of(group.text, { w -> phonemizer.phonemize(w).size }, audio.size)
                        } else {
                            emptyList()
                        }
                        rendered.send(Rendered(audio, group.start, group.end, timing))
                    }
                } finally {
                    rendered.close()
                }
            }
            if (flavorPitch != null && !player.interrupted) {
                // The "brrp!" announces the character before the words — and
                // now before the first synthesis finishes, so a flavored line
                // answers instantly even when its words are seconds away.
                player.play(ParrotEffect.flourish(SAMPLE_RATE))
            }
            for (item in rendered) {
                if (player.interrupted) break
                emit(TtsEvent.RangeSpoken(item.start, item.end))
                val timing = item.timing
                if (timing.isEmpty()) {
                    player.play(item.audio)
                } else {
                    // Driven by the PLAYBACK HEAD, not the writer: the word
                    // that lights up is the word that is sounding. The first
                    // timing entry starts at frame 0, so last{} never misses.
                    player.play(item.audio) { frames ->
                        val w = timing.last { frames >= it.startFrame }
                        Karaoke.set(text, item.start + w.charStart, item.start + w.charEnd)
                    }
                }
            }
            producer.cancelAndJoin()
        }
        Karaoke.clear()
        player.release()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming path: each incoming chunk is already a sentence (the
     * orchestrator runs SentenceChunker on the LLM token stream), so synthesis
     * starts on the FIRST sentence while later ones are still being generated.
     * Playback order is preserved because the rendezvous channel is FIFO and
     * the playing loop is sequential; synthesis of the next group overlaps the
     * sounding of the current one exactly as in [speakInternal].
     */
    override fun speakStream(chunks: Flow<String>, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        player.interrupted = false
        emit(TtsEvent.Started)
        coroutineScope {
            val rendered = Channel<Rendered>(Channel.RENDEZVOUS)
            val producer = launch(Dispatchers.IO) {
                try {
                    // First sentence alone — it IS the latency win streaming
                    // exists for. After that, sentences are paired before
                    // synthesis so the contour spans sentence boundaries (see
                    // the prosody note in speakInternal). The LLM decodes
                    // several times faster than speech plays, so by the time a
                    // pair is spoken the next pair has long since arrived —
                    // the pairing costs no audible gap.
                    var first = true
                    val pending = StringBuilder()
                    var pendingCount = 0
                    suspend fun render(text: String, label: String) {
                        if (player.interrupted) return
                        val audio = renderOrCache(text, speed, flavorPitch = null, label = label)
                        if (audio.isNotEmpty()) rendered.send(Rendered(audio, -1, -1))
                    }
                    chunks.collect { sentence ->
                        if (player.interrupted) return@collect
                        if (first) {
                            first = false
                            render(sentence, "stream")
                        } else {
                            if (pending.isNotEmpty()) pending.append(' ')
                            pending.append(sentence)
                            pendingCount++
                            if (pendingCount >= GROUP_MAX_SENTENCES || pending.length >= GROUP_TARGET_CHARS) {
                                render(pending.toString(), "stream pair")
                                pending.setLength(0)
                                pendingCount = 0
                            }
                        }
                    }
                    if (pending.isNotEmpty()) render(pending.toString(), "stream tail")
                } finally {
                    rendered.close()
                }
            }
            for (item in rendered) {
                if (player.interrupted) break
                player.play(item.audio)
            }
            producer.cancelAndJoin()
        }
        player.release()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    /** A synthesized group waiting its turn at the speaker. Offsets are the
     *  source-text range for karaoke ([TtsEvent.RangeSpoken]), or -1 when the
     *  stream has no stable offsets to report. */
    private class Rendered(
        val audio: FloatArray,
        val start: Int,
        val end: Int,
        /** Estimated word starts for karaoke; empty when nothing tracks. */
        val timing: List<KaraokeTiming.Word> = emptyList(),
    )

    /**
     * One group of text to one waveform, through the cache when eligible.
     *
     * Lines the app repeats — praise, prompts, drill targets — come back from
     * the cache already voiced. "Great job!" measured 2977 ms to synthesize on
     * device and the drill room says it after every correct answer;
     * recomputing a byte-identical waveform on a CPU that is already thermally
     * throttled is the cheapest waste in the app to remove. The key carries
     * everything that changes the sound, and the cache holds several
     * renditions per line so the repetition is still varied (see [SynthCache]).
     * A hit also skips phonemization — only lines that phonemized before can
     * be in the cache. Both speech paths call this, so short repeated lines
     * from a STREAMED reply are ~0.2 s too (they used to bypass the cache).
     */
    private fun renderOrCache(text: String, speed: Float, flavorPitch: Float?, label: String): FloatArray {
        val cacheKey = if (SynthCache.eligible(text)) {
            SynthCache.key(text, voiceAsset, flavorPitch, speed)
        } else {
            null
        }
        cacheKey?.let { SynthCache.get(it) }?.let { return it }
        val ids = phonemizer.phonemize(text)
        if (ids.isEmpty()) return FloatArray(0)
        // Flavored lines synthesize SLOWER by the pitch factor, so the
        // resample inside the effect lands the duration back where it was
        // asked for while pitch and formants ride up together.
        val synthSpeed = if (flavorPitch != null) vary(speed) / flavorPitch else vary(speed)
        var fresh = EngineStatus.step(EngineStatus.Kind.TTS_RUN, "${ids.size} phonemes ($label)") {
            synthesize(ids, synthSpeed)
        }
        if (flavorPitch != null && fresh.isNotEmpty()) {
            fresh = ParrotEffect.apply(fresh, SAMPLE_RATE, flavorPitch)
        }
        // Cached AFTER the effect: the flavor is part of the sound, and the
        // pitch is part of the key.
        if (cacheKey != null) SynthCache.put(cacheKey, fresh)
        return fresh
    }

    override suspend fun stop() {
        player.interrupted = true
        Karaoke.clear()
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
                val seconds = audio.size / SAMPLE_RATE.toFloat()
                // RTF is the number that decides whether this engine is
                // viable at all: 1.0 means synthesis takes as long as the
                // speech lasts, so the room can never feel responsive.
                val rtf = if (seconds > 0f) ms / 1000f / seconds else Float.NaN
                Log.i(
                    tag,
                    "synth ${ids.size} tokens -> " +
                        "${"%.2f".format(seconds)}s in ${ms}ms rtf=${"%.2f".format(rtf)} " +
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
class ParrotVoice(
    private val engine: KokoroTtsEngine,
    private val pitch: Float = ParrotEffect.PITCH,
) : TtsEngine {

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> =
        engine.speakFlavored(text, speed, pitch)

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

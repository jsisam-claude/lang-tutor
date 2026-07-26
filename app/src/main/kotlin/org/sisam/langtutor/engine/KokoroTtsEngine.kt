package org.sisam.langtutor.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import org.sisam.langtutor.speech.TtsEngine
import org.sisam.langtutor.speech.TtsEvent
import org.sisam.langtutor.speech.TutorLanguage

/**
 * Bundled Kokoro-82M voice — Tuki speaks with OUR OWN on-device TTS, no Google
 * services and no system voices needed (GrapheneOS has neither). Single-graph
 * ONNX build (StyleTTS2 family): phoneme ids + style vector + speed in, 24 kHz
 * waveform out; [KokoroPhonemizer] provides the ids, the af_heart voice ships
 * in APK assets (fetched + SHA-256-pinned by scripts/fetch-voice-assets.sh),
 * and the 86 MB model installs like the LLM (Parent Zone pack / import).
 *
 * [AppContainer] holds ONE instance app-wide: the ORT session mmaps the model
 * once and is reused across sessions (it is stateless per call).
 *
 * DEVICE-VERIFY (docs/bench.md): per-sentence synth RTF on Tensor CPU — logcat
 * tag [TAG] prints ms per chunk vs seconds of audio.
 */
class KokoroTtsEngine(context: Context, private val modelFile: File) : TtsEngine {

    private val appContext = context.applicationContext
    private val phonemizer by lazy { KokoroPhonemizer.load() }

    /** 510 rows × 256 floats; row (tokens+2-1) conditions the voice, upstream convention. */
    private val voice: FloatArray by lazy {
        appContext.assets.open(VOICE_ASSET).use { input ->
            val bytes = input.readBytes()
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            floats
        }
    }

    private val session: OrtSession by lazy {
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

    @Volatile private var interrupted = false
    @Volatile private var track: AudioTrack? = null

    override fun speak(text: String, language: TutorLanguage, speed: Float): Flow<TtsEvent> = flow {
        interrupted = false
        emit(TtsEvent.Started)
        // Kokoro is an English voice; Hebrew letters phonemize to nothing and the
        // chunk is skipped, so a stray Hebrew line degrades to silence, not a crash.
        for (chunk in sentenceChunks(text)) {
            if (interrupted) break
            val ids = phonemizer.phonemize(chunk.text)
            if (ids.isEmpty()) continue
            emit(TtsEvent.RangeSpoken(chunk.start, chunk.end))
            val audio = synthesize(ids, speed)
            if (audio.isNotEmpty() && !interrupted) play(audio)
        }
        releaseTrack()
        emit(TtsEvent.Completed)
    }.flowOn(Dispatchers.IO)

    override suspend fun stop() {
        interrupted = true
        releaseTrack()
    }

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
            session.run(inputs).use { results ->
                val buf = (results[0] as OnnxTensor).floatBuffer
                val audio = FloatArray(buf.remaining())
                buf.get(audio)
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "synth ${ids.size} tokens -> ${"%.2f".format(audio.size / SAMPLE_RATE.toFloat())}s in ${ms}ms")
                return audio
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun play(audio: FloatArray) {
        val t = track ?: AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(SAMPLE_RATE * Float.SIZE_BYTES) // 1 s of headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        var written = 0
        while (written < audio.size && !interrupted) {
            val n = t.write(audio, written, audio.size - written, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            written += n
        }
        // Drain so Completed means "finished sounding", not "finished writing" —
        // the orchestrator opens the mic right after and must not hear Tuki.
        val deadline = System.currentTimeMillis() + (audio.size * 1000L / SAMPLE_RATE) + DRAIN_GRACE_MS
        while (!interrupted && t.playbackHeadPosition < written && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun releaseTrack() {
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
    }

    /** Split on sentence enders (keeping them) so long replies stream out sentence by sentence. */
    private fun sentenceChunks(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in SENTENCE_ENDERS && (i == text.length - 1 || text[i + 1].isWhitespace())) {
                val piece = text.substring(start, i + 1).trim()
                if (piece.isNotEmpty()) chunks.add(Chunk(piece, start, i + 1))
                start = i + 1
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) chunks.add(Chunk(tail, start, text.length))
        return chunks
    }

    private data class Chunk(val text: String, val start: Int, val end: Int)

    companion object {
        private const val TAG = "TukiTts"
        private const val SAMPLE_RATE = 24_000
        private const val STYLE_DIM = 256
        private const val THREADS = 4
        private const val DRAIN_GRACE_MS = 700L
        private val SENTENCE_ENDERS = ".!?".toSet()

        /** af_heart from onnx-community/Kokoro-82M-v1.0-ONNX, pinned by the fetch script. */
        const val VOICE_ASSET = "kokoro/af_heart.bin"
    }
}

package org.sisam.langtutor.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sisam.langtutor.speech.AsrEngine
import org.sisam.langtutor.speech.AsrResult
import org.sisam.langtutor.speech.AudioClip
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.speech.VadGate
import org.sisam.langtutor.speech.WhisperFrontend
import org.sisam.langtutor.speech.WhisperGreedyDecoder
import org.sisam.langtutor.speech.WhisperTokenizer
import org.tensorflow.lite.Interpreter

/**
 * BUNDLED on-device ASR — our own stack, no Google services, no network:
 * push-to-talk PCM -> WhisperFrontend (golden-tested log-mel) -> the two-
 * signature Whisper tflite (litert-community int4 export) via the LiteRT
 * interpreter -> greedy decode loop -> bundled tokenizer. This is what makes
 * the mic work on de-googled devices (GrapheneOS), where no platform speech
 * service exists.
 *
 * DEVICE-VERIFY: the decode mask is assumed additive-causal (0 past, -1e9
 * future). If transcripts come out as garbage, the export may expect a
 * multiplicative 1/0 mask — one-line flip below.
 */
class WhisperAsrEngine(
    private val modelFile: File,
    /** When present, the engine can end the turn by itself (hands-free). */
    private val vad: SileroVad? = null,
) : AsrEngine {

    private var interpreter: Interpreter? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var capturing = false
    @Volatile private var endpoint: CompletableDeferred<Unit>? = null

    override val supportsHandsFree: Boolean get() = vad != null

    @SuppressLint("MissingPermission") // RECORD_AUDIO requested by ConversationScreen
    override suspend fun startCapture(hint: RecognitionHint) {
        stopRecorderQuietly()
        chunks.clear()
        val gate = vad?.let { VadGate() }
        vad?.reset()
        val signal = CompletableDeferred<Unit>()
        endpoint = signal
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // Read in whole VAD frames so the detector never sees a partial window.
        val readSize = maxOf(minBuf, SileroVad.FRAME * 4) / SileroVad.FRAME * SileroVad.FRAME
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, readSize * 4)
        recorder = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread {
            val buf = ShortArray(readSize)
            val frame = FloatArray(SileroVad.FRAME)
            var total = 0
            while (capturing && total < MAX_SAMPLES) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                chunks.add(buf.copyOf(n))
                total += n
                if (gate == null || vad == null || signal.isCompleted) continue
                var off = 0
                while (off + SileroVad.FRAME <= n) {
                    for (i in 0 until SileroVad.FRAME) frame[i] = buf[off + i] / 32768f
                    off += SileroVad.FRAME
                    val event = runCatching { gate.accept(vad.probability(frame)) }
                        .onFailure { Log.w(TAG, "vad frame failed", it) }
                        .getOrNull() ?: continue
                    if (event is VadGate.Event.SpeechEnd) {
                        Log.i(TAG, "endpoint: ${event.reason} frames ${event.startFrame}..${event.endFrame}")
                        signal.complete(Unit)
                        break
                    }
                }
            }
            // Capture ended without the gate firing (button release / max length).
            signal.complete(Unit)
        }.also { it.start() }
    }

    /** Hands-free: resumes when the bundled VAD says the child stopped talking. */
    override suspend fun awaitEndpoint() {
        endpoint?.await()
    }

    override suspend fun stopCapture(): AsrResult = withContext(Dispatchers.Default) {
        capturing = false
        captureThread?.join(2000)
        stopRecorderQuietly()
        endpoint?.complete(Unit)
        endpoint = null
        val total = chunks.sumOf { it.size }
        if (total < SAMPLE_RATE / 4) return@withContext AsrResult("", 0f) // <0.25 s: nothing said
        val pcm = FloatArray(total)
        val pcm16 = ShortArray(total)
        var i = 0
        for (c in chunks) for (s in c) {
            pcm16[i] = s
            pcm[i++] = s / 32768f
        }
        chunks.clear()
        try {
            val text = transcribe(pcm)
            AsrResult(
                transcript = text.trim(),
                confidence = if (text.isBlank()) 0f else CONFIDENCE,
                // Retained for this turn so pronunciation scoring can run on the
                // very audio that was transcribed.
                audio = AudioClip(pcm16, SAMPLE_RATE),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed", t)
            AsrResult("", 0f)
        }
    }

    private fun transcribe(pcm: FloatArray): String {
        val itp = interpreter ?: Interpreter(
            modelFile,
            Interpreter.Options().apply { setNumThreads(THREADS) },
        ).also {
            interpreter = it
            Log.i(TAG, "loaded ${modelFile.name}")
        }

        var t0 = System.nanoTime()
        val mel = WhisperFrontend.logMel(pcm)
        val melIn = arrayOf(mel) // [1,80,3000]
        val melMs = (System.nanoTime() - t0) / 1_000_000

        t0 = System.nanoTime()
        val encOut = Array(1) { Array(ENC_FRAMES) { FloatArray(ENC_DIM) } }
        itp.runSignature(mapOf("args_0" to melIn), mapOf("output_0" to encOut), "encode")
        val encMs = (System.nanoTime() - t0) / 1_000_000

        // Static additive causal mask; rows past the current count are never read.
        val mask = Array(1) { Array(1) { Array(MAX_TOK) { r -> FloatArray(MAX_TOK) { c -> if (c <= r) 0f else NEG_INF } } } }
        val tokenBuf = Array(1) { IntArray(MAX_TOK) }
        val logitsOut = Array(1) { Array(MAX_TOK) { FloatArray(WhisperTokenizer.VOCAB_SIZE) } }

        t0 = System.nanoTime()
        var steps = 0
        val ids = WhisperGreedyDecoder { tokens, count ->
            System.arraycopy(tokens, 0, tokenBuf[0], 0, MAX_TOK)
            itp.runSignature(
                mapOf("args_0" to encOut, "args_1" to tokenBuf, "args_2" to mask),
                mapOf("output_0" to logitsOut),
                "decode",
            )
            steps++
            logitsOut[0][count - 1]
        }.transcribe()
        val decMs = (System.nanoTime() - t0) / 1_000_000

        val text = WhisperTokenizer.decode(ids)
        Log.i(TAG, "mel=${melMs}ms encode=${encMs}ms decode=${decMs}ms/${steps}steps -> ${ids.size} tokens")
        return text
    }

    private fun stopRecorderQuietly() {
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
    }

    private companion object {
        const val TAG = "TukiAsr"
        const val SAMPLE_RATE = WhisperFrontend.SAMPLE_RATE
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_SAMPLES = SAMPLE_RATE * 30
        const val ENC_FRAMES = 1500
        const val ENC_DIM = 1024
        const val MAX_TOK = WhisperGreedyDecoder.MAX_TOKENS
        const val NEG_INF = -1e9f
        const val CONFIDENCE = 0.85f
        const val THREADS = 6
    }
}

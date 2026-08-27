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
import org.sisam.langtutor.speech.AudioChunker
import org.sisam.langtutor.speech.AudioClip
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.speech.VadGate
import org.sisam.langtutor.speech.WhisperFrontend
import org.sisam.langtutor.speech.WhisperLayout
import org.sisam.langtutor.speech.WhisperGreedyDecoder
import org.sisam.langtutor.speech.WhisperTokenizer
import org.tensorflow.lite.Interpreter

/**
 * BUNDLED on-device ASR — our own stack, no Google services, no network:
 * PCM -> WhisperFrontend (golden-tested log-mel) -> the two-signature Whisper
 * tflite via the LiteRT interpreter -> greedy decode loop -> bundled tokenizer.
 * This is what makes the mic work on de-googled devices (GrapheneOS), where no
 * platform speech service exists.
 *
 * The graph's geometry (window length, encoder shape, token layout) is read
 * from the loaded model's own signatures rather than assumed, so both the
 * classic 30 s exports and the short-window ACFT exports work on one code
 * path. The short window is what the app installs: a 10 s export encodes 1000
 * mel frames instead of 3000, which measured ~12x faster than the 30 s medium
 * export at the same accuracy on child-length phrases, and it cannot drift into
 * 28 seconds of padding the way a 30 s window occasionally does
 * (docs/asr-model-eval.md).
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
        // Local val: a class property can't be smart-cast inside the capture
        // lambda, and the inner loop must call the detector without a null check
        // on every frame.
        val detector = vad
        val gate = detector?.let { VadGate() }
        detector?.reset()
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
                if (gate == null || detector == null || signal.isCompleted) continue
                var off = 0
                while (off + SileroVad.FRAME <= n) {
                    for (i in 0 until SileroVad.FRAME) frame[i] = buf[off + i] / 32768f
                    off += SileroVad.FRAME
                    val event = runCatching { gate.accept(detector.probability(frame)) }
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
                // REAL decoder confidence — the hardcoded 0.85 made the
                // policy's "please say that again" branch unreachable.
                confidence = if (text.isBlank()) 0f else lastConfidence,
                // Retained for this turn so pronunciation scoring can run on the
                // very audio that was transcribed.
                audio = AudioClip(pcm16, SAMPLE_RATE),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed", t)
            AsrResult("", 0f)
        }
    }

    /** Geometry + token layout read from the loaded export, not assumed. */
    private data class Graph(
        val melFrames: Int,   // 3000 (30 s) or 1000/500 for short-window ACFT
        val encFrames: Int,
        val encDim: Int,
        val maxTokens: Int,
        val layout: WhisperLayout,
        /** The decode signature's OWN output width. May differ from
         *  layout.vocabSize when forVocabSize() fell back to MULTILINGUAL for an
         *  export we do not recognise — size the buffer from the graph, always. */
        val vocabSize: Int,
    )

    private var graph: Graph? = null

    private fun describe(itp: Interpreter): Graph {
        val enc = itp.getInputTensorFromSignature("args_0", "encode").shape()   // [1,80,frames]
        val decAudio = itp.getInputTensorFromSignature("args_0", "decode").shape() // [1,encF,dim]
        val decTokens = itp.getInputTensorFromSignature("args_1", "decode").shape() // [1,maxTok]
        val decOut = itp.getOutputTensorFromSignature("output_0", "decode").shape() // [1,maxTok,vocab]
        return Graph(
            melFrames = enc[2],
            encFrames = decAudio[1],
            encDim = decAudio[2],
            maxTokens = decTokens[1],
            layout = WhisperLayout.forVocabSize(decOut[2]),
            vocabSize = decOut[2],
        ).also {
            Log.i(
                TAG,
                "graph: window ${it.melFrames * WhisperFrontend.HOP / SAMPLE_RATE}s " +
                    "mel[1,80,${it.melFrames}] enc[1,${it.encFrames},${it.encDim}] " +
                    "maxTok=${it.maxTokens} vocab=${it.vocabSize} layout=${it.layout}",
            )
            if (it.vocabSize != it.layout.vocabSize) {
                Log.w(
                    TAG,
                    "UNRECOGNISED export: vocab ${it.vocabSize} matches no known layout; " +
                        "decoding with ${it.layout} special-token ids — transcripts may be garbage",
                )
            }
        }
    }

    /**
     * Frees the interpreter (and its mapped weights) under memory pressure; the
     * next utterance reloads it, visibly, via the ASR_LOAD step.
     */
    @Synchronized
    fun release() {
        interpreter?.let {
            runCatching { it.close() }
            interpreter = null
            graph = null
            Log.i(TAG, "interpreter released (memory pressure)")
        }
    }

    /**
     * Loads the interpreter up front so the first mic press does not pay a
     * hundreds-of-MB model load inside the turn. Same memoised instance the
     * first transcription would have created.
     */
    @Synchronized
    fun warmUp() {
        loadInterpreter()
    }

    // First call pays the model load (hundreds of MB); it is reported as its
    // own step so the UI can show it separately from the work around it.
    private fun loadInterpreter(): Interpreter =
        interpreter ?: EngineStatus.step(
            EngineStatus.Kind.ASR_LOAD,
            modelFile.name,
        ) {
            createInterpreter().also {
                interpreter = it
                graph = describe(it)
            }
        }

    /**
     * CPU only, and that is a MEASURED verdict rather than caution.
     *
     * The LiteRT GPU delegate was tried on 2026-08-27 and failed three ways at
     * once. It was SLOWER (rtf 2.14 climbing to 4.20 against ~2.8 on CPU). It
     * wrecked accuracy — confidence fell from 0.91 to 0.40-0.54 and the decoder
     * emitted one to three tokens for a second and a half of speech, which is
     * the dynamic-range-quantized partition falling back op by op exactly as
     * feared. And worst, it appears to have taken the LLM's GPU backend down
     * with it: two GPU runtimes initialising 96 ms apart (this delegate, then
     * LiteRT-LM's accelerator) crashed the process natively twice in a row,
     * which pinned the language model to CPU and cost far more than the ears
     * could ever have won.
     *
     * Do not retry without a way to keep the two GPU contexts apart.
     */
    private fun createInterpreter(): Interpreter =
        Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(THREADS) })

    // @Synchronized so release() waits for an in-flight transcription.
    @Synchronized
    private fun transcribe(pcm: FloatArray): String {
        val itp = loadInterpreter()
        val g = graph ?: describe(itp).also { graph = it }

        // The frontend truncates to the export's window, so anything past it
        // would be silently dropped — split first and transcribe each piece.
        val pieces = AudioChunker.split(pcm, g.melFrames * WhisperFrontend.HOP)
        if (pieces.size > 1) Log.i(TAG, "utterance split into ${pieces.size} windows")
        val audioMs = pcm.size * 1000L / SAMPLE_RATE
        val asrStarted = System.currentTimeMillis()

        // Reused across pieces: the mask is static and the buffers are shaped by
        // the graph, not by the audio.
        val mask = Array(1) {
            Array(1) { Array(g.maxTokens) { r -> FloatArray(g.maxTokens) { c -> if (c <= r) 0f else NEG_INF } } }
        }
        val tokenBuf = Array(1) { IntArray(g.maxTokens) }
        val logitsOut = Array(1) { Array(g.maxTokens) { FloatArray(g.vocabSize) } }
        val encOut = Array(1) { Array(g.encFrames) { FloatArray(g.encDim) } }
        val tokenizer = WhisperTokenizer.of(g.layout)

        val windows = pieces.mapIndexed { index, piece ->
            EngineStatus.step(
                EngineStatus.Kind.ASR_RUN,
                if (pieces.size > 1) "window ${index + 1}/${pieces.size}" else "",
            ) { transcribeWindow(itp, g, piece, mask, tokenBuf, logitsOut, encOut, tokenizer) }
        }
        // Token-weighted mean: a long clean window should not be dragged to 0.5
        // by a two-token tail, and an empty window contributes nothing.
        val tokens = windows.sumOf { it.tokens }
        lastConfidence = if (tokens == 0) 0f
        else (windows.sumOf { (it.avgProb * it.tokens).toDouble() } / tokens).toFloat()
        // RTF against the speech actually captured, not the padded window —
        // "3.2s to transcribe" means nothing until you know it was 1.1s of
        // audio. Same headline number the voice logs, so the two compare.
        val elapsed = System.currentTimeMillis() - asrStarted
        val rtf = if (audioMs > 0) elapsed / audioMs.toFloat() else Float.NaN
        Log.i(
            TAG,
            "transcribed ${audioMs}ms audio in ${elapsed}ms rtf=${"%.2f".format(rtf)} " +
                "conf=${"%.2f".format(lastConfidence)}${Thermal.suffix()}",
        )
        return windows.joinToString(" ") { it.text }.trim()
    }

    /** Decoder-reported confidence of the LAST [transcribe] call. */
    @Volatile private var lastConfidence = 0f

    @Suppress("LongParameterList") // one window's worth of reused graph buffers
    private fun transcribeWindow(
        itp: Interpreter,
        g: Graph,
        piece: FloatArray,
        mask: Array<Array<Array<FloatArray>>>,
        tokenBuf: Array<IntArray>,
        logitsOut: Array<Array<FloatArray>>,
        encOut: Array<Array<FloatArray>>,
        tokenizer: WhisperTokenizer,
    ): Window {
        var t0 = System.nanoTime()
        val melIn = arrayOf(WhisperFrontend.logMel(piece, g.melFrames)) // [1,80,frames]
        val melMs = (System.nanoTime() - t0) / 1_000_000

        t0 = System.nanoTime()
        itp.runSignature(mapOf("args_0" to melIn), mapOf("output_0" to encOut), "encode")
        val encMs = (System.nanoTime() - t0) / 1_000_000

        t0 = System.nanoTime()
        var steps = 0
        val decoded = WhisperGreedyDecoder(maxTokens = g.maxTokens, layout = g.layout) { tokens, count ->
            System.arraycopy(tokens, 0, tokenBuf[0], 0, g.maxTokens)
            itp.runSignature(
                mapOf("args_0" to encOut, "args_1" to tokenBuf, "args_2" to mask),
                mapOf("output_0" to logitsOut),
                "decode",
            )
            steps++
            logitsOut[0][count - 1]
        }.transcribe()
        val decMs = (System.nanoTime() - t0) / 1_000_000

        Log.i(
            TAG,
            "mel=${melMs}ms encode=${encMs}ms decode=${decMs}ms/${steps}steps " +
                "-> ${decoded.ids.size} tokens, conf=${"%.2f".format(decoded.avgProb)}",
        )
        return Window(tokenizer.decode(decoded.ids).trim(), decoded.avgProb, decoded.ids.size)
    }

    /** One window's transcript plus the decoder's belief in it. */
    private data class Window(val text: String, val avgProb: Float, val tokens: Int)

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
        // Hard ceiling on one turn. Independent of the model's window: audio
        // longer than the window is split by AudioChunker, not thrown away.
        const val MAX_SAMPLES = SAMPLE_RATE * 30
        const val NEG_INF = -1e9f

        // Half the cores, not all of them. These graphs are dynamic-range
        // quantized: XNNPACK splits the reductions by thread count, so the
        // thread count changes the summation order and — on a marginal frame —
        // the winning token. Measured in-container, transcripts of identical
        // audio got measurably worse once the threads matched the core count
        // (docs/asr-model-eval.md). The short-window model has the latency
        // headroom to be conservative here. DEVICE-VERIFY on the 9a.
        /** Same budget as the ONNX engines: the fast cores, not all of them.
         *  Four threads on a 1x-X4 + 3x-A720 + 4x-A520 phone saturates exactly
         *  the cores that get hot — see [OnnxTuning] for the measurements. */
        val THREADS = OnnxTuning.heavyThreads

    }
}

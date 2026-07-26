package org.sisam.langtutor.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import java.io.File
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.GenerationStats
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role

/**
 * REAL on-device LLM engine — Google's LiteRT-LM runtime driving the exact
 * `.litertlm` Gemma 4 artifact the app ships (see the native-runtime eval in
 * eval/hebrew/results/VERDICT.md: E4B passes the Hebrew gate at 4.45 on this
 * runtime). This is the production replacement for [org.sisam.langtutor.llm.FakeLlmEngine];
 * [AppContainer] wires it in whenever the model file is present on device.
 *
 * The engine is heavy: [load] mmaps multi-GB weights and must run off the main
 * thread; per the thermal budget (docs/feasibility.md §3) the orchestrator
 * loads it for a session and [unload]s it afterwards.
 *
 * On-device verification note: this compiles against the real LiteRT-LM 0.14.0
 * AAR, but only a physical Pixel exercises the GPU/TPU path and confirms decode
 * throughput (docs/bench.md). Two behaviours are marked as device-verify points
 * below (streaming delta semantics, accelerator fallback).
 *
 * @param modelPath absolute filesystem path to the `.litertlm` file, resolved by
 *   the caller from the installed model pack / asset pack.
 */
class LiteRtLmEngine(private val modelPath: String) : LlmEngine {

    private var engine: Engine? = null

    override suspend fun load(spec: LlmModelSpec) = withContext(Dispatchers.Default) {
        // Prefer the accelerator; fall back to CPU if the delegate can't bind
        // (e.g. no Tensor TPU / GPU on the device). The failure surfaces at
        // initialize(), so we try backends in order. DEVICE-VERIFY: which of
        // GPU/NPU actually accelerates Gemma 4 on Tensor is the Pixel bench.
        //
        // Devices where GPU generation is proven broken (GrapheneOS: WebGPU
        // sampler needs an OpenCL/.so Google doesn't ship) get a hint file so
        // session start skips the doomed multi-second GPU attempt. The hint is
        // keyed to this build's versionCode: an app update (which may bundle
        // the missing sampler library) retries GPU once and re-decides.
        val hintFile = File("$modelPath$CPU_HINT_SUFFIX")
        val skipGpu = runCatching {
            hintFile.isFile && hintFile.readText().trim() == BuildConfig.VERSION_CODE.toString()
        }.getOrDefault(false)
        if (skipGpu) Log.i(TAG, "cpu hint present for this build — skipping the GPU attempt")

        var lastError: Throwable? = null
        var gpuFailed = false
        val backends = if (skipGpu) listOf("cpu" to Backend.CPU())
        else listOf("gpu" to Backend.GPU(), "cpu" to Backend.CPU())
        for ((label, backend) in backends) {
            val started = System.nanoTime()
            val candidate = try {
                Engine(EngineConfig(modelPath = modelPath, backend = backend))
            } catch (t: Throwable) {
                Log.w(TAG, "engine create failed on $backend", t)
                lastError = t
                if (label == "gpu") gpuFailed = true
                continue
            }
            try {
                candidate.initialize()
                // initialize() succeeding does NOT prove the backend can generate:
                // on GrapheneOS the WebGPU executor initializes fine but the top-K
                // sampler needs OpenCL (absent there) and errors only on the first
                // generation. Smoke-test one tiny turn before accepting the
                // backend — doubles as kernel warm-up on healthy devices.
                smokeTest(candidate)
                engine = candidate
                val ms = (System.nanoTime() - started) / 1_000_000
                Log.i(TAG, "loaded $modelPath on backend=$backend in ${ms}ms (smoke ok)")
                runCatching {
                    when {
                        // CPU only works after GPU just failed: remember, skip next time.
                        label == "cpu" && gpuFailed -> hintFile.writeText(BuildConfig.VERSION_CODE.toString())
                        // GPU works: clear any stale hint from an older build.
                        label == "gpu" && hintFile.exists() -> hintFile.delete()
                    }
                }
                return@withContext
            } catch (t: Throwable) {
                // Release the half-initialized engine before falling back, or the
                // failed attempt's native buffers leak alongside the next backend.
                runCatching { candidate.close() }
                Log.w(TAG, "initialize failed on $backend", t)
                lastError = t
                if (label == "gpu") gpuFailed = true
            }
        }
        Log.e(TAG, "load failed on ALL backends for $modelPath", lastError)
        throw IllegalStateException("LiteRT-LM failed to load $modelPath on any backend", lastError)
    }

    override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
        val active = engine ?: error("generate() called before load()")
        val conversation = active.createConversation(
            com.google.ai.edge.litertlm.ConversationConfig(
                systemInstruction = Contents.of(request.systemPrompt),
                initialMessages = request.messages.dropLast(1).mapNotNull { it.toLiteRt() },
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = DEFAULT_TOP_P,
                    temperature = request.temperature.toDouble(),
                ),
            ),
        )
        val userText = request.messages.lastOrNull()?.text.orEmpty()
        val full = StringBuilder()
        val startNanos = System.nanoTime()
        // Streamed chunks are NOT tokens (a chunk may carry several). All token
        // counts here use the ~4-chars-per-token heuristic, clearly an ESTIMATE:
        // the cap is a character budget and tok/s in stats is estimated the same
        // way. DEVICE-VERIFY (docs/bench.md): calibrate against real token counts
        // before treating the bench numbers as authoritative.
        val charBudget = request.maxTokens * EST_CHARS_PER_TOKEN
        var firstDeltaMs = -1L
        try {
            // DEVICE-VERIFY: we treat each streamed Message.text as a delta (the
            // doc's `collect { print(it) }` prints each chunk). If the runtime
            // turns out to emit cumulative text, switch to delta = text.removePrefix(full).
            conversation.sendMessageAsync(userText)
                .takeWhile { full.length < charBudget }
                .collect { message ->
                    // Text comes out via toString() — the documented extraction
                    // (getting_started uses `print(sendMessage(...))` and
                    // `collect { print(it.toString()) }`). DEVICE-VERIFY: confirm
                    // this is clean text with no role framing on real output.
                    val delta = message.toString()
                    if (delta.isNotEmpty()) {
                        if (firstDeltaMs < 0) {
                            firstDeltaMs = (System.nanoTime() - startNanos) / 1_000_000
                            Log.i(TAG, "first token after ${firstDeltaMs}ms (delta len=${delta.length})")
                        }
                        full.append(delta)
                        emit(LlmEvent.Token(delta))
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed after ${(System.nanoTime() - startNanos) / 1_000_000}ms", t)
            throw t
        } finally {
            conversation.close()
        }
        val seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val estTokens = full.length / EST_CHARS_PER_TOKEN
        Log.i(TAG, "turn done: ${full.length} chars in ${"%.1f".format(seconds)}s (~${(estTokens / seconds.coerceAtLeast(0.001)).toInt()} est-tok/s)")
        emit(
            LlmEvent.Done(
                fullText = full.toString(),
                stats = GenerationStats(
                    promptTokens = request.systemPrompt.length / EST_CHARS_PER_TOKEN,
                    completionTokens = estTokens,
                    decodeTokensPerSecond = if (seconds > 0) (estTokens / seconds).toFloat() else 0f,
                ),
            ),
        )
    }.flowOn(Dispatchers.Default)

    override suspend fun unload() = withContext(Dispatchers.Default) {
        engine?.close()
        engine = null
    }

    /** One minimal end-to-end generation; throws if the backend cannot sample.
     *  MUST use the production sampler settings: greedy (topK=1) can bypass the
     *  top-K sampler whose OpenCL dependency is exactly what breaks on
     *  GrapheneOS — a greedy smoke could pass on a backend real turns fail on. */
    private fun smokeTest(candidate: Engine) {
        val conversation = candidate.createConversation(
            com.google.ai.edge.litertlm.ConversationConfig(
                systemInstruction = Contents.of("Reply with one short word."),
                initialMessages = emptyList(),
                samplerConfig = SamplerConfig(topK = DEFAULT_TOP_K, topP = DEFAULT_TOP_P, temperature = 0.7),
            ),
        )
        try {
            val reply = conversation.sendMessage("Hi").toString()
            check(reply.isNotBlank()) { "backend produced an empty smoke reply" }
            Log.i(TAG, "smoke reply ok (${reply.length} chars)")
        } finally {
            conversation.close()
        }
    }

    private fun ChatMessage.toLiteRt(): Message? = when (role) {
        Role.USER -> Message.user(text)
        Role.ASSISTANT -> Message.model(text)
        Role.SYSTEM -> null // system prompt is carried by ConversationConfig.systemInstruction
    }

    companion object {
        private const val TAG = "TukiLlm"

        // Matches the sampling used in the eval harness (run_eval_litert.py).
        private const val DEFAULT_TOP_K = 64
        private const val DEFAULT_TOP_P = 0.95

        // Rough tokens≈chars/4 heuristic for budgets and stats (see generate()).
        private const val EST_CHARS_PER_TOKEN = 4

        // Marker next to the model file: "GPU generation broken on this device
        // for this app build — go straight to CPU". See load().
        private const val CPU_HINT_SUFFIX = ".cpu-hint"
    }
}

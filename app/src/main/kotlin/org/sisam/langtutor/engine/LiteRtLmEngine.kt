package org.sisam.langtutor.engine

import com.google.ai.edge.litertlm.Backend
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
        var lastError: Throwable? = null
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            try {
                val candidate = Engine(EngineConfig(modelPath = modelPath, backend = backend))
                candidate.initialize()
                engine = candidate
                return@withContext
            } catch (t: Throwable) {
                lastError = t
            }
        }
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
        var tokens = 0
        val startNanos = System.nanoTime()
        try {
            // DEVICE-VERIFY: we treat each streamed Message.text as a delta (the
            // doc's `collect { print(it) }` prints each chunk). If the runtime
            // turns out to emit cumulative text, switch to delta = text.removePrefix(full).
            conversation.sendMessageAsync(userText)
                .takeWhile { tokens < request.maxTokens }
                .collect { message ->
                    // Text comes out via toString() — the documented extraction
                    // (getting_started uses `print(sendMessage(...))` and
                    // `collect { print(it.toString()) }`). DEVICE-VERIFY: confirm
                    // this is clean text with no role framing on real output.
                    val delta = message.toString()
                    if (delta.isNotEmpty()) {
                        full.append(delta)
                        tokens++
                        emit(LlmEvent.Token(delta))
                    }
                }
        } finally {
            conversation.close()
        }
        val seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        emit(
            LlmEvent.Done(
                fullText = full.toString(),
                stats = GenerationStats(
                    promptTokens = request.systemPrompt.length / 4,
                    completionTokens = tokens,
                    decodeTokensPerSecond = if (seconds > 0) (tokens / seconds).toFloat() else 0f,
                ),
            ),
        )
    }.flowOn(Dispatchers.Default)

    override suspend fun unload() = withContext(Dispatchers.Default) {
        engine?.close()
        engine = null
    }

    private fun ChatMessage.toLiteRt(): Message? = when (role) {
        Role.USER -> Message.user(text)
        Role.ASSISTANT -> Message.model(text)
        Role.SYSTEM -> null // system prompt is carried by ConversationConfig.systemInstruction
    }

    companion object {
        // Matches the sampling used in the eval harness (run_eval_litert.py).
        private const val DEFAULT_TOP_K = 64
        private const val DEFAULT_TOP_P = 0.95
    }
}

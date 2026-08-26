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
import org.sisam.langtutor.llm.ConvoReuse
import org.sisam.langtutor.llm.ConvoState
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
class LiteRtLmEngine(
    private val modelPath: String,
    /**
     * Identity of THIS install, scoping the "GPU doesn't work here" hint.
     *
     * versionCode alone was wrong: it is hard-coded to 1 and never bumped in
     * development, so ONE early GPU failure pinned CPU permanently — including
     * across the very rebuilds that ADD a missing accelerator library. Keyed to
     * the install instead, each new APK gets exactly one fresh GPU attempt, and
     * a device that genuinely cannot do GPU still pays for it only once.
     */
    private val installStamp: String,
) : LlmEngine {

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
        val buildStamp = installStamp
        val attemptMarker = File("$modelPath$GPU_ATTEMPT_SUFFIX")
        val hintSkip = runCatching {
            hintFile.isFile && hintFile.readText().trim() == buildStamp
        }.getOrDefault(false)
        // Crash-loop guard: a leftover attempt marker means the last GPU attempt
        // never completed — a NATIVE crash (e.g. inside the GPU driver or a
        // bundled sampler lib) killed the process where Kotlin catches nothing.
        // Pin CPU for this build so one bad driver can't crash every launch.
        val crashSkip = attemptMarker.exists()
        if (crashSkip) {
            Log.w(TAG, "previous GPU attempt crashed the process — pinning CPU for this build ($buildStamp)")
            runCatching { hintFile.writeText(buildStamp) }
            runCatching { attemptMarker.delete() }
        } else if (hintSkip) {
            Log.i(
                TAG,
                "cpu hint matches this install ($buildStamp) — skipping the GPU attempt; " +
                    "reinstall to retry GPU, or delete $CPU_HINT_SUFFIX next to the model",
            )
        }
        val skipGpu = hintSkip || crashSkip

        var lastError: Throwable? = null
        var gpuFailed = false
        val backends = if (skipGpu) listOf("cpu" to Backend.CPU(cpuThreads(), null))
        else listOf("gpu" to Backend.GPU(), "cpu" to Backend.CPU(cpuThreads(), null))
        for ((label, backend) in backends) {
            if (label == "gpu") runCatching { attemptMarker.createNewFile() }
            val started = System.nanoTime()
            // Multi-GB mmap plus accelerator warm-up, and a failed backend is
            // paid before the fallback: report each attempt by name so a long
            // wait is legible as "trying GPU" rather than a hang.
            val loadStep = EngineStatus.begin(
                EngineStatus.Kind.LLM_LOAD,
                "${File(modelPath).name} on $label",
            )
            val candidate = try {
                Engine(EngineConfig(modelPath = modelPath, backend = backend))
            } catch (t: Throwable) {
                EngineStatus.end(loadStep, t)
                Log.w(TAG, "engine create failed on $backend", t)
                runCatching { attemptMarker.delete() }
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
                EngineStatus.end(loadStep, null)
                Log.i(
                    TAG,
                    "loaded $modelPath on backend=$backend in ${ms}ms (smoke ok); " +
                        "cores=${Runtime.getRuntime().availableProcessors()} cpuThreads=${cpuThreads()}",
                )
                // One unmissable line answering "why not GPU?" — otherwise the
                // answer is the ABSENCE of a log line, which is invisible.
                Log.i(
                    TAG,
                    "GPU verdict: " + when {
                        label == "gpu" -> "USED"
                        crashSkip -> "skipped — a previous attempt crashed the process natively"
                        hintSkip -> "skipped — cpu hint from an earlier failure on this install"
                        gpuFailed -> "attempted and FAILED, see the warning above: " +
                            "${lastError?.javaClass?.simpleName}: ${lastError?.message}"
                        else -> "not attempted"
                    },
                )
                runCatching { attemptMarker.delete() }
                runCatching {
                    when {
                        // CPU only works after GPU just failed: remember, skip next time.
                        label == "cpu" && gpuFailed -> hintFile.writeText(buildStamp)
                        // GPU works: clear any stale hint from an older build.
                        label == "gpu" && hintFile.exists() -> hintFile.delete()
                    }
                }
                return@withContext
            } catch (t: Throwable) {
                // Release the half-initialized engine before falling back, or the
                // failed attempt's native buffers leak alongside the next backend.
                runCatching { candidate.close() }
                runCatching { attemptMarker.delete() }
                EngineStatus.end(loadStep, t)
                Log.w(TAG, "initialize failed on $backend", t)
                lastError = t
                if (label == "gpu") gpuFailed = true
            }
        }
        Log.e(TAG, "load failed on ALL backends for $modelPath", lastError)
        throw IllegalStateException("LiteRT-LM failed to load $modelPath on any backend", lastError)
    }

    // --- conversation reuse (the Pixel 9 latency lever) -----------------------
    // One live runtime Conversation per session, so turn N prefills ONE new
    // message instead of the system prompt + the whole history window. The
    // reuse decision lives in core/llm (ConvoReuse, unit-tested); this class
    // just tracks state and acts on the verdict. AppContainer creates one
    // engine per orchestrator, so the cache is naturally session-scoped.
    private var convo: com.google.ai.edge.litertlm.Conversation? = null
    private var convoState: ConvoState? = null

    @Synchronized
    private fun closeConvo() {
        runCatching { convo?.close() }
        convo = null
        convoState = null
    }

    /** Reuse the live conversation for a continuation, else rebuild. */
    @Synchronized
    private fun acquireConversation(
        active: Engine,
        request: LlmRequest,
        systemText: String,
        prior: List<ChatMessage>,
    ): com.google.ai.edge.litertlm.Conversation {
        val existing = convo
        val priorTexts = prior.map { it.text }
        if (existing != null &&
            ConvoReuse.canReuse(convoState, systemText, request.temperature, priorTexts)
        ) {
            Log.i(TAG, "convo reuse: prefilling 1 message instead of ${prior.size + 1}")
            return existing
        }
        closeConvo()
        val estTokens = (systemText.length + prior.sumOf { it.text.length }) / EST_CHARS_PER_TOKEN
        Log.i(TAG, "convo rebuild: prefilling ${prior.size} messages (~$estTokens est tokens)")
        return active.createConversation(
            com.google.ai.edge.litertlm.ConversationConfig(
                systemInstruction = Contents.of(systemText),
                initialMessages = prior.mapNotNull { it.toLiteRt() },
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = DEFAULT_TOP_P,
                    temperature = request.temperature.toDouble(),
                ),
            ),
        ).also {
            convo = it
            convoState = ConvoState(systemText, request.temperature, priorTexts, estTokens, dirty = false)
        }
    }

    /** Called from the turn's finally: keep a clean conversation, drop a dirty one. */
    @Synchronized
    private fun onTurnFinished(
        conversation: com.google.ai.edge.litertlm.Conversation,
        clean: Boolean,
        userText: String,
        replyText: String,
    ) {
        if (convo !== conversation) return // unload() or a rebuild got here first
        if (clean) {
            // Record what the conversation actually processed this turn, so the
            // next call can prove its window is a continuation of THIS history.
            convoState = convoState?.let {
                it.copy(
                    seen = it.seen + userText + replyText,
                    estTokens = it.estTokens + (userText.length + replyText.length) / EST_CHARS_PER_TOKEN,
                )
            }
        } else {
            closeConvo()
        }
    }

    override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
        val active = engine ?: error("generate() called before load()")
        // Leading SYSTEM messages carry the policy's per-turn lesson guidance
        // ("the child is practicing X — praise, recast, one question"). They
        // used to be dropped entirely — toLiteRt maps SYSTEM to null — so the
        // model never saw the lesson. They belong in the system text.
        val prior = request.messages.dropLast(1)
        val systemText = (listOf(request.systemPrompt) +
            prior.filter { it.role == Role.SYSTEM }.map { it.text })
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val conversation = acquireConversation(active, request, systemText, prior)
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
        // Reported only until the first token lands: after that the streaming
        // reply is its own progress indicator, and two spinners would compete.
        // Prefill is the genuinely silent part.
        val genStep = EngineStatus.begin(EngineStatus.Kind.LLM_GENERATE, "prefill")
        var waitingForFirstToken = true
        var failed = false
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
                            if (waitingForFirstToken) {
                                waitingForFirstToken = false
                                EngineStatus.end(genStep, null)
                            }
                        }
                        full.append(delta)
                        emit(LlmEvent.Token(delta))
                    }
                }
        } catch (t: Throwable) {
            failed = true
            Log.e(TAG, "generate failed after ${(System.nanoTime() - startNanos) / 1_000_000}ms", t)
            throw t
        } finally {
            // A reply that produced nothing at all still has to close its step.
            if (waitingForFirstToken) EngineStatus.end(genStep, null)
            // Keep the conversation ONLY after a clean, uncut stream: after an
            // exception, a cancellation, or a charBudget cut its internal
            // history is unknowable (and a cancelled generation may still hold
            // the runtime), so it is closed and the next turn rebuilds from the
            // request — which is exactly the pre-reuse behavior. DEVICE-VERIFY:
            // flow cancellation is assumed to stop the underlying generation.
            onTurnFinished(
                conversation,
                clean = !failed && full.length < charBudget,
                userText = userText,
                replyText = full.toString(),
            )
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

    override fun invalidateContext() = closeConvo()

    override suspend fun unload() = withContext(Dispatchers.Default) {
        closeConvo()
        engine?.close()
        engine = null
    }

    /** One minimal end-to-end generation; throws if the backend cannot sample.
     *  MUST use the production sampler settings: greedy (topK=1) can bypass the
     *  top-K sampler whose OpenCL dependency is exactly what breaks on
     *  GrapheneOS — a greedy smoke could pass on a backend real turns fail on. */
    /**
     * Threads for CPU decode. Passing nothing left LiteRT-LM on its own default
     * (the device logged `CPU(threadCount=null, numOfThreads=null)`), which is
     * the wrong shape for a big.LITTLE phone: Tensor G4 is 1x Cortex-X4 +
     * 3x A720 + 4x A520, and scheduling decode onto the little cores makes them
     * stragglers that the fast cores wait on every token. Half the core count,
     * clamped, lands on the big cluster — 4 on a Pixel 9 — without hard-coding
     * a topology we cannot actually query.
     *
     * DEVICE-VERIFY: this is a reasoned default, not a measured optimum. The
     * decode tok/s in the bench is what should settle the number.
     */
    private fun cpuThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

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

        // Present only WHILE a GPU attempt is in flight; surviving a process
        // death, it marks the attempt as having crashed natively. See load().
        private const val GPU_ATTEMPT_SUFFIX = ".gpu-attempting"
    }
}

package org.sisam.langtutor.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Directory for the runtime's compiled-model cache, or null for none.
     * The GPU backend compiles kernels/graph on load; with a cache dir the
     * compilation is reused, which is the difference between every session
     * paying a cold load and only the first one paying it.
     */
    private val cacheDir: String? = null,
) : LlmEngine {

    private var engine: Engine? = null

    // One loader at a time, and loading twice is a no-op: the launch-time
    // preload pass and a session's startSession() race here by design, and
    // without the guard the loser built a SECOND multi-GB native engine and
    // leaked the winner's. unload() nulls `engine`, so load() after unload is
    // a real (re)load, preserving the load/unload thermal budget.
    private val loadMutex = Mutex()

    override suspend fun load(spec: LlmModelSpec): Unit = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            if (engine == null) loadLocked()
        }
    }

    private fun loadLocked() {
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

        // Multi-Token Prediction (speculative decoding): the runtime drafts
        // several tokens per step and verifies them in one pass — upstream
        // quotes up to ~2.2x decode on mobile. It is NOT in EngineConfig; the
        // switch is a global experimental flag, and whether a given .litertlm
        // can use it is a property of the export ("not supported for model
        // without per layer embedding" — Gemma's PLE architecture qualifies).
        // Ask before enabling, so an export without MTP heads is not forced
        // down a path the runtime will reject.
        val mtpSupported = runCatching {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        }.onFailure { Log.w(TAG, "MTP: capability probe failed, treating as unsupported", it) }
            .getOrDefault(false)
        Log.i(TAG, "MTP: export ${if (mtpSupported) "supports" else "does not support"} speculative decoding")

        // MTP ON GPU, retried — with attribution this time.
        //
        // It was disabled for the GPU attempt after a native crash on a Pixel 9
        // that had just run GPU cleanly. But that build enabled MTP *and* the
        // compile cache together, so the crash was unattributable and BOTH were
        // pulled. The condition for retrying was "a way to attribute the
        // crash", and that is what the separate marker below provides: the GPU
        // attempt now has two of them, so a crash names which variant died and
        // the fallback steps down one rung instead of all the way to CPU.
        // Upstream quotes up to ~2.2x decode, and the device currently manages
        // 7-8 est-tok/s on GPU against a documented 12-14, so it is worth the
        // one variable.
        val mtpGpuHint = File("$modelPath$MTP_GPU_HINT_SUFFIX")
        val mtpGpuMarker = File("$modelPath$MTP_GPU_ATTEMPT_SUFFIX")
        // Not when the compile-cache latch also fired: that one is newer and
        // takes the blame alone, so MTP keeps its turn (see below).
        val gpuCacheAlsoCrashed = File("$modelPath$GPU_CACHE_ATTEMPT_SUFFIX").exists()
        val mtpGpuCrashed = mtpGpuMarker.exists() && !gpuCacheAlsoCrashed
        if (mtpGpuCrashed) {
            Log.w(TAG, "MTP+GPU crashed the process last time — GPU without MTP for this build ($buildStamp)")
            runCatching { mtpGpuHint.writeText(buildStamp) }
            runCatching { mtpGpuMarker.delete() }
        }
        val mtpGpuHinted = runCatching {
            mtpGpuHint.isFile && mtpGpuHint.readText().trim() == buildStamp
        }.getOrDefault(false)
        val skipMtpGpu = mtpGpuCrashed || mtpGpuHinted

        // THE COMPILE CACHE ON GPU, retried — this is where the load time is.
        //
        // A device log times the GPU load at 26.6s and 28.3s, and 22.5-23.8s of
        // each is a single gap: between "Replacing 2712 out of 2712 node(s)
        // with delegate (LITERT_CL) ... subgraph 0 (decode)" and the same line
        // for the next subgraph. That is OpenCL compiling the decode kernels,
        // and persisting exactly that is what cacheDir is for. Every load pays
        // it today, including the reload after a room exit.
        //
        // It was pulled from the GPU attempt because the build that turned on
        // the cache AND MTP together crashed natively — unattributable, so both
        // went. MTP came back first and has since loaded cleanly three times in
        // one session, and the unload/generate race that could crash a native
        // decode has been fixed. So the cache gets its own latch and its turn.
        //
        // Demotion is newest-suspect-first: a crash during a gpu+mtp+cache
        // attempt trips BOTH latches, so this one is resolved first and clears
        // the MTP marker without acting on it. The next launch runs gpu+mtp
        // with no cache — one variable removed, not two.
        val gpuCacheHint = File("$modelPath$GPU_CACHE_HINT_SUFFIX")
        val gpuCacheMarker = File("$modelPath$GPU_CACHE_ATTEMPT_SUFFIX")
        val gpuCacheCrashed = gpuCacheMarker.exists()
        if (gpuCacheCrashed) {
            Log.w(TAG, "compile cache on GPU crashed the process last time — GPU without it for this build ($buildStamp)")
            runCatching { gpuCacheHint.writeText(buildStamp) }
            runCatching { gpuCacheMarker.delete() }
        }
        val gpuCacheHinted = runCatching {
            gpuCacheHint.isFile && gpuCacheHint.readText().trim() == buildStamp
        }.getOrDefault(false)
        val useGpuCache = cacheDir != null && !gpuCacheCrashed && !gpuCacheHinted

        // THE TPU, actually asked instead of assumed.
        //
        // The docs said the Edge TPU was closed to us on Tensor G4 on three
        // grounds, one of which a device log has now put in doubt. Android's
        // nativeloader prints, on this Pixel 9:
        //
        //   InitVendorPublicLibraries: libOpenCL.so:libOpenCL-pixel.so:
        //     libedgetpu_client.google.so:libedgetpu_util.so:lib_aion_buffer.so:
        //     lib_jpg_encoder.so:libgxp.so:libedgetpu_tachyon.google.so:
        //     libedgetpu_litert.so
        //
        // Those are the vendor libraries an ordinary app IS allowed to link
        // against, and the Edge TPU ones are on the list. Meanwhile the 0.16.1
        // API turns out to carry Backend.NPU(nativeLibraryDir) — which is
        // precisely the "You should provide the DispatchLibraryDir option to
        // use NPU" the runtime warns about six times per load — and a
        // parameterless Backend.GOOGLE_TENSOR().
        //
        // So the honest position is that we have never tried. This rung tries,
        // costs nothing when it fails (a caught exception, then straight on to
        // GPU), and is skipped entirely on hardware with no dispatch library.
        // What we expect to learn either way: if it is really SELinux denying
        // untrusted_app on /dev/gxp, the log will say so as an avc denial, and
        // that turns a claim we took on authority into a measurement.
        val npuLibDir = NPU_LIB_DIRS.firstOrNull { dir ->
            NPU_DISPATCH_LIBS.any { File(dir, it).exists() }
        }
        val npuHint = File("$modelPath$NPU_HINT_SUFFIX")
        val npuMarker = File("$modelPath$NPU_ATTEMPT_SUFFIX")
        val npuCrashed = npuMarker.exists()
        if (npuCrashed) {
            Log.w(TAG, "NPU attempt crashed the process last time — skipping it for this build ($buildStamp)")
            runCatching { npuHint.writeText(buildStamp) }
            runCatching { npuMarker.delete() }
        }
        val npuHinted = runCatching {
            npuHint.isFile && npuHint.readText().trim() == buildStamp
        }.getOrDefault(false)
        val tryNpu = npuLibDir != null && !npuCrashed && !npuHinted
        Log.i(
            TAG,
            "NPU: " + when {
                npuLibDir == null -> "no dispatch library on this device (looked in ${NPU_LIB_DIRS.joinToString(", ")}) — not attempted"
                npuCrashed || npuHinted -> "pinned off for this build after an earlier failure"
                else -> "dispatch library found in $npuLibDir — will attempt"
            },
        )

        var lastError: Throwable? = null
        var gpuFailed = false
        // Each rung is (label, backend, MTP) with its OWN crash marker, so a
        // native death is attributable to the exact combination that caused it.
        val backends = buildList {
            // First, and only where a dispatch library actually exists. MTP is
            // off here: the drafter is a second graph, and one unknown at a
            // time is the rule this ladder is built on.
            if (tryNpu && npuLibDir != null) {
                add(Triple("npu", Backend.NPU(npuLibDir), false))
            }
            if (!skipGpu) {
                if (mtpSupported && !skipMtpGpu) add(Triple("gpu+mtp", Backend.GPU(), true))
                add(Triple("gpu", Backend.GPU(), false))
            }
            // CPU keeps MTP unconditionally: measured at ~4-7 est-tok/s with
            // it against ~1.6 without, on the same device and log.
            add(Triple("cpu", Backend.CPU(cpuThreads(), null), mtpSupported))
        }
        for ((label, backend, mtp) in backends) {
            @OptIn(ExperimentalApi::class)
            runCatching { ExperimentalFlags.enableSpeculativeDecoding = mtp }
            val marker = when (label) {
                "npu" -> npuMarker
                "gpu+mtp" -> mtpGpuMarker
                "gpu" -> attemptMarker
                else -> null
            }
            runCatching { marker?.createNewFile() }
            // A second latch on the SAME attempt: the cache is a modifier on
            // the GPU rungs rather than a rung of its own, so it needs its own
            // marker to be blamed separately from the backend underneath it.
            if (useGpuCache && label.startsWith("gpu")) {
                runCatching { gpuCacheMarker.createNewFile() }
            }
            val started = System.nanoTime()
            // Multi-GB mmap plus accelerator warm-up, and a failed backend is
            // paid before the fallback: report each attempt by name so a long
            // wait is legible as "trying GPU" rather than a hang.
            val loadStep = EngineStatus.begin(
                EngineStatus.Kind.LLM_LOAD,
                "${File(modelPath).name} on $label",
            )
            val candidate = try {
                // Exclusive: another runtime bringing up an accelerator at the
                // same moment is what crashed this process natively, and what
                // made the marker files blame the wrong attempt.
                AcceleratorGate.exclusive(label) {
                Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        // KV cache is pre-allocated for this whole window, on
                        // whichever backend wins — the runtime default (4096)
                        // doubles that memory for conversations that are ten
                        // short child turns. 2048 is generous for our prompts
                        // and matters most to the GPU attempt, where the KV
                        // block competes with the weights for device memory.
                        maxNumTokens = MAX_CONTEXT_TOKENS,
                        // CPU attempt only, same evidence as the MTP scoping
                        // above: the GPU attempt ran cleanly WITHOUT the cache
                        // and crashed natively in the build that added it (the
                        // other suspect being MTP; unattributable from one
                        // crash, so the GPU attempt carries neither). Today's
                        // log shows CPU loading fine with it (3.9s warm vs
                        // 15.4s cold — suggestive, not attributed).
                        // CPU always; GPU unless its own latch says the
                        // cache is what killed the process on this device.
                        cacheDir = if (label == "cpu" || label == "npu" || useGpuCache) cacheDir else null,
                    ),
                ).also { it.initialize() }
                }
            } catch (t: Throwable) {
                EngineStatus.end(loadStep, t)
                Log.w(TAG, "engine create failed on $label", t)
                runCatching { marker?.delete() }
                runCatching { gpuCacheMarker.delete() }
                lastError = t
                if (label.startsWith("gpu")) gpuFailed = true
                continue
            }
            try {
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
                    "loaded $modelPath on $label (mtp=$mtp) in ${ms}ms (smoke ok); " +
                        "cores=${Runtime.getRuntime().availableProcessors()} cpuThreads=${cpuThreads()}",
                )
                // One unmissable line answering "why not GPU?" — otherwise the
                // answer is the ABSENCE of a log line, which is invisible.
                Log.i(
                    TAG,
                    "GPU verdict: " + when {
                        label == "npu" -> "not used — the NPU took this load (see the NPU line above)"
                        label == "gpu+mtp" -> "USED with MTP (speculative decoding)" +
                            if (useGpuCache) " + compile cache" else ", no compile cache"
                        label == "gpu" && skipMtpGpu -> "USED without MTP — MTP+GPU is pinned off for this build"
                        label == "gpu" && !mtpSupported -> "USED — this export has no MTP heads"
                        label == "gpu" -> "USED without MTP"
                        crashSkip -> "skipped — a previous attempt crashed the process natively"
                        hintSkip -> "skipped — cpu hint from an earlier failure on this install"
                        gpuFailed -> "attempted and FAILED, see the warning above: " +
                            "${lastError?.javaClass?.simpleName}: ${lastError?.message}"
                        else -> "not attempted"
                    },
                )
                runCatching { marker?.delete() }
                runCatching { gpuCacheMarker.delete() }
                runCatching {
                    when {
                        // CPU only works after GPU just failed: remember, skip next time.
                        label == "cpu" && gpuFailed -> hintFile.writeText(buildStamp)
                        // Any GPU rung working clears a stale hint from an
                        // older build; the MTP rung additionally clears its own.
                        label == "npu" -> { if (npuHint.exists()) npuHint.delete() }
                        label.startsWith("gpu") -> {
                            if (hintFile.exists()) hintFile.delete()
                            if (label == "gpu+mtp" && mtpGpuHint.exists()) mtpGpuHint.delete()
                            if (useGpuCache && gpuCacheHint.exists()) gpuCacheHint.delete()
                        }
                    }
                }
                return
            } catch (t: Throwable) {
                // Release the half-initialized engine before falling back, or the
                // failed attempt's native buffers leak alongside the next backend.
                runCatching { candidate.close() }
                runCatching { attemptMarker.delete() }
                runCatching { gpuCacheMarker.delete() }
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
        // Reload rather than fail: the container releases the engine when the
        // app sits in the background past its grace period, and a session the
        // learner RETURNS to must pick up where it left off, not die on its
        // next turn. load() is mutex-guarded and idempotent, so this line
        // costs one null check in the common case and a full (status-reported)
        // reload only when the background policy actually fired.
        // The mutex is held for the WHOLE turn, not just to read the engine:
        // unload() takes the same one, so a timed background release arriving
        // mid-generation waits instead of freeing native state under a running
        // graph. loadLocked(), not load(), because the mutex is not reentrant.
        loadMutex.withLock {
        if (engine == null) loadLocked()
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
        // Split the phases: lumping prefill into one tok/s number made an 8s
        // turn read as "~1 est-tok/s" when most of it was the silent prefill
        // of the system prompt + history. TTFT is what the child WAITS;
        // decode-phase rate is what streaming then rides on — different
        // problems, different fixes, so report them separately.
        val decodeSeconds =
            if (firstDeltaMs > 0) (seconds - firstDeltaMs / 1000.0).coerceAtLeast(0.001) else seconds
        Log.i(
            TAG,
            "turn done: ${full.length} chars in ${"%.1f".format(seconds)}s " +
                "(ttft=${firstDeltaMs}ms, decode ~${(estTokens / decodeSeconds).toInt()} est-tok/s)",
        )
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
        }
    }.flowOn(Dispatchers.Default)

    override fun invalidateContext() = closeConvo()

    /**
     * Release the engine under the SAME mutex that guards loading and
     * generating.
     *
     * It used to take no lock, which was survivable while unloading only
     * happened on a screen exit the user had just performed. It is not
     * survivable now: the container releases engines on a timer once the app
     * has been backgrounded a few minutes, and generate() reloads on demand —
     * so close() could land mid-decode, and freeing native state under a
     * running graph is a SIGSEGV, not an exception. A crash inside
     * liblitertlm_jni.so was observed on device (2026-08-27) minutes after a
     * background release. Now a release arriving mid-turn simply waits.
     */
    override suspend fun unload() = withContext(Dispatchers.Default) {
        loadMutex.withLock {
            closeConvo()
            runCatching { engine?.close() }
            engine = null
        }
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
        /** KV-cache window. Ten short child turns + a 96-token reply fit with
         *  lots of room; the runtime default (4096) doubles the pre-allocated
         *  cache for nothing. */
        private const val MAX_CONTEXT_TOKENS = 2048

        private const val CPU_HINT_SUFFIX = ".cpu-hint"

        // Present only WHILE a GPU attempt is in flight; surviving a process
        // death, it marks the attempt as having crashed natively. See load().
        private const val GPU_ATTEMPT_SUFFIX = ".gpu-attempting"

        /** Separate markers for the MTP-on-GPU rung, so a native crash names
         *  WHICH GPU variant died instead of condemning the backend. */
        private const val MTP_GPU_ATTEMPT_SUFFIX = ".gpu-mtp-attempting"
        private const val MTP_GPU_HINT_SUFFIX = ".gpu-mtp-skip"
        private const val GPU_CACHE_ATTEMPT_SUFFIX = ".gpu-cache-attempting"
        private const val GPU_CACHE_HINT_SUFFIX = ".gpu-cache-skip"
        private const val NPU_ATTEMPT_SUFFIX = ".npu-attempting"
        private const val NPU_HINT_SUFFIX = ".npu-skip"

        /** Where a vendor image puts the libraries nativeloader exposes. */
        private val NPU_LIB_DIRS = listOf("/vendor/lib64", "/system/vendor/lib64", "/odm/lib64")

        /** Any one of these present means there is something to dispatch to. */
        private val NPU_DISPATCH_LIBS = listOf(
            "libedgetpu_litert.so",
            "libedgetpu_client.google.so",
        )
    }
}

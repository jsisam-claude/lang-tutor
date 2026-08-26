package org.sisam.langtutor

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.engine.HebrewTtsEngine
import org.sisam.langtutor.engine.KokoroTtsEngine
import org.sisam.langtutor.engine.LiteRtLmEngine
import org.sisam.langtutor.engine.PlatformAsrEngine
import org.sisam.langtutor.engine.PlatformTtsEngine
import org.sisam.langtutor.engine.RewardChime
import org.sisam.langtutor.engine.SileroVad
import org.sisam.langtutor.engine.TtsRouter
import org.sisam.langtutor.engine.Wav2Vec2GopEngine
import org.sisam.langtutor.engine.WhisperAsrEngine
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.speech.TukiVoice
import org.sisam.langtutor.speech.TukiVoices
import org.sisam.langtutor.tutor.chat.ChatRoom
import org.sisam.langtutor.tutor.chat.ChatSpeaker
import org.sisam.langtutor.speech.TutorLanguage
import org.sisam.langtutor.llm.LlmTierPolicy
import org.sisam.langtutor.packs.HttpPackFetcher
import org.sisam.langtutor.packs.PackRepository
import org.sisam.langtutor.packs.RealPackRepository
import org.sisam.langtutor.packs.ResourceCatalogLoader
import org.sisam.langtutor.packs.ramTierGb
import org.sisam.langtutor.profile.JsonFileProfileStore
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.PronunciationScorer
import org.sisam.langtutor.tutor.ScriptedDialoguePolicy
import org.sisam.langtutor.tutor.TutorOrchestrator
import org.sisam.langtutor.ui.reward.RewardBus
import org.sisam.langtutor.ui.reward.RewardKind

/**
 * Manual composition root — the single swap point for engines.
 *
 * Current wiring: REAL speech via the platform dev shims (on-device
 * SpeechRecognizer + TextToSpeech) so the voice loop works on a phone today;
 * the LLM is the REAL LiteRT-LM engine [LiteRtLmEngine] the moment the model
 * file is on device (bundled asset pack or an installed download), and falls
 * back to the scripted [FakeLlmEngine] until then — so the app runs end-to-end
 * on an emulator/CI without weights, and lights up real generation on a Pixel
 * once the model lands, with no code change.
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * App-lifetime scope for work that must outlive any one screen — most
     * importantly multi-GB pack downloads, which previously died when the user
     * navigated away from the Parent Zone mid-download.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Real device RAM tier (GB), detected from ActivityManager — drives which
     * model packs are offered: Pixel 9a (8 GB) → E2B base; Pixel 9 (12 GB) and
     * 10 Pro XL (16 GB) → E4B quality. Replaces the old hardcoded assumption.
     */
    val deviceRamGb: Int = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        ramTierGb(info.totalMem)
    }

    init {
        // React to memory pressure by dropping idle engine sessions — the
        // difference between "the coach reloads in a couple of seconds" and
        // "Android killed the app mid-conversation". Matters most on the 12 GB
        // Pixel 9 running the E4B brain, where the speech stack's ~0.7-1 GB of
        // anonymous memory is exactly the margin the LLM needs.
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = trimEngines(level)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = trimEngines(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        })
    }

    /**
     * Free what can be cheaply rebuilt. Light pressure drops the per-feature
     * engines (coach ~320 MB, Hebrew ~450 MB — a 1-3 s reload on next use,
     * reported by the status line). Real pressure also drops the ASR
     * interpreter and the English voice. The LLM is deliberately NOT dropped
     * here: its reload is ~40 s, its weights are mmapped (the kernel can
     * already evict them page by page), and killing the conversation to
     * maybe-save the process is a worse trade than letting Android decide.
     * (The TRIM_MEMORY_RUNNING_* constants are deprecated in API 34 but still
     * delivered; this stays correct on every OS we target.)
     */
    private fun trimEngines(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val deep = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        appScope.launch {
            runCatching { gopEngine?.release() }
            runCatching { hebrewEngine?.release() }
            if (deep) {
                runCatching { whisperEngine?.release() }
                runCatching { kokoroEngine?.release() }
            }
            Log.i(MEM_TAG, "trim level=$level -> released coach+hebrew" + if (deep) "+asr+voice" else "")
        }
    }

    val content: ContentRepository = ResourceContentRepository()

    val profile: LearnerProfileStore =
        JsonFileProfileStore(File(context.filesDir, "profile.json").toPath())

    // User-approved enhancement downloads, real streaming downloader. Installs
    // into filesDir, so a downloaded model lands at the exact path the engine
    // reads (models/gemma-4-E4B-it.litertlm) — no adb push needed. Every download
    // is user-initiated from the Parent Zone; nothing is uploaded.
    val packs: PackRepository = RealPackRepository(
        catalog = ResourceCatalogLoader.load(),
        installRoot = appContext.filesDir,
        fetcher = HttpPackFetcher(),
        insecureFetcher = HttpPackFetcher(insecureTls = true),
    )

    /**
     * No-adb, no-network model sideload: copies a file the user picked with the
     * system file picker into files/models (SHA-256-verified). See [ModelImporter].
     */
    val modelImporter = ModelImporter(appContext, appScope)

    /**
     * TESTING ONLY (debug builds): disable TLS certificate verification for pack
     * downloads — a workaround for an intercepting network / untrusted CA on the
     * test device. The downloaded model is STILL SHA-256-verified, so a tampered
     * file is still rejected. Triggered from PacksSection behind a BuildConfig.DEBUG
     * gate + an explicit warning dialog; never reachable in a release build.
     */
    fun enableInsecureDownloads() {
        // Hard release guard: even if a code path ever reached here in a release
        // build, the bypass stays off.
        if (!BuildConfig.DEBUG) return
        (packs as? RealPackRepository)?.allowInsecureTls = true
    }

    /**
     * Bundled Whisper model if present (pushed/downloaded next to the LLM) —
     * our own ASR with no Google services, which is what makes the mic work on
     * de-googled devices. Resolved fresh like [modelFile].
     */
    val bundledAsrFile: File?
        get() {
            val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            for (base in bases) for (name in ASR_CANDIDATES) {
                val f = File(base, name)
                if (f.exists() && f.length() > 0L) return f
            }
            return null
        }

    val hasBundledAsr: Boolean get() = bundledAsrFile != null

    /** Bundled Kokoro voice model if present — our own TTS, no system voices. */
    val bundledTtsFile: File?
        get() {
            val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            for (base in bases) for (name in TTS_CANDIDATES) {
                val f = File(base, name)
                if (f.exists() && f.length() > 0L) return f
            }
            return null
        }

    val hasBundledTts: Boolean get() = bundledTtsFile != null

    /**
     * Bundled Hebrew voice — needs BOTH Phonikud files (nikud model + Piper
     * voice); with only one installed the router keeps English-only behavior.
     */
    private val hebrewTtsFiles: Pair<File, File>?
        get() {
            val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            for (base in bases) {
                val nikud = File(base, TTS_HE_NIKUD_PATH)
                val voice = File(base, TTS_HE_VOICE_PATH)
                if (nikud.exists() && nikud.length() > 0L && voice.exists() && voice.length() > 0L) {
                    return nikud to voice
                }
            }
            return null
        }

    val hasHebrewTts: Boolean get() = hebrewTtsFiles != null

    /**
     * ONE Kokoro engine app-wide, keyed by model path: the ORT session holds the
     * 86 MB graph and is stateless per call, so recreating it per conversation
     * screen would leak native sessions on every visit.
     */
    @Volatile private var kokoroEngine: KokoroTtsEngine? = null
    @Volatile private var kokoroPath: String? = null

    private fun bundledTtsEngine(): KokoroTtsEngine? {
        val file = bundledTtsFile ?: return null
        // A build made without scripts/fetch-voice-assets.sh has no voice asset;
        // honor the documented fallback (platform TTS) instead of crashing the
        // first spoken turn with a missing-asset exception.
        if (!hasVoiceAsset) return null
        kokoroEngine?.takeIf { kokoroPath == file.absolutePath }?.let { return it }
        synchronized(this) {
            kokoroEngine?.takeIf { kokoroPath == file.absolutePath }?.let { return it }
            return KokoroTtsEngine(appContext, file).also {
                kokoroEngine = it
                kokoroPath = file.absolutePath
            }
        }
    }

    private val hasVoiceAsset: Boolean by lazy {
        runCatching {
            appContext.assets.open("${KokoroTtsEngine.VOICE_DIR}/${TukiVoices.DEFAULT_ID}").close()
        }.isSuccess
    }

    /**
     * Applies the parent's chosen voice to the live engine.
     *
     * Switching is only loading a different 510x256 conditioning table, so it
     * takes effect on the next sentence with no model reload — which is what
     * makes previewing voices in the picker feel instant.
     */
    /** The catalogue filtered to voices actually packaged in THIS APK: a local
     *  build made without re-running fetch-voice-assets.sh carries fewer than
     *  the full set, and the picker must not offer what it cannot play. */
    val availableVoices: List<TukiVoice> by lazy {
        val present = runCatching {
            appContext.assets.list(KokoroTtsEngine.VOICE_DIR)?.toSet()
        }.getOrNull() ?: emptySet()
        TukiVoices.ALL.filter { it.id in present }
    }

    fun applyVoice(voiceId: String?) {
        val chosen = TukiVoices.byId(voiceId)
        val effective = if (availableVoices.any { it.id == chosen.id }) chosen else TukiVoices.byId(null)
        if (effective.id != chosen.id) {
            Log.w(MEM_TAG, "voice ${chosen.id} not packaged in this build; using ${effective.id}")
        }
        bundledTtsEngine()?.voiceAsset = effective.id
    }

    private val _speaking = MutableStateFlow(false)

    /**
     * True while a preview or voice test is actually sounding.
     *
     * Exposed so the picker can animate Tuki in step with the sample — the
     * point of the picker is how a voice SOUNDS, and a child watching a parent
     * choose should see the bird move for each one.
     */
    val speaking: StateFlow<Boolean> = _speaking

    /** Speaks the test line in a specific voice, for previewing in the picker. */
    fun previewVoice(voiceId: String): Job = appScope.launch(Dispatchers.IO) {
        applyVoice(voiceId)
        speakTestLine()
    }

    private suspend fun speakTestLine() {
        val tts = TtsRouter(
            english = bundledTtsEngine() ?: PlatformTtsEngine(appContext),
            hebrew = hebrewTtsEngine(),
        )
        _speaking.value = true
        try {
            runCatching { tts.speak(VOICE_TEST_LINE, TutorLanguage.ENGLISH).collect { } }
                .onFailure { Log.w(MEM_TAG, "voice preview failed", it) }
        } finally {
            _speaking.value = false
        }
    }

    /** One Hebrew engine app-wide, same reasoning as [kokoroEngine]. */
    @Volatile private var hebrewEngine: HebrewTtsEngine? = null
    @Volatile private var hebrewPath: String? = null

    /**
     * One Whisper engine app-wide too: each instance lazily holds a LiteRT
     * Interpreter with ~0.7 GB of weights and nothing ever closed it — a new
     * engine per conversation screen leaked an interpreter per visit.
     */
    @Volatile private var whisperEngine: WhisperAsrEngine? = null
    @Volatile private var whisperPath: String? = null

    private fun bundledAsrEngine(): WhisperAsrEngine? {
        val file = bundledAsrFile ?: return null
        whisperEngine?.takeIf { whisperPath == file.absolutePath }?.let { return it }
        synchronized(this) {
            whisperEngine?.takeIf { whisperPath == file.absolutePath }?.let { return it }
            return WhisperAsrEngine(file, vad = sileroVad()).also {
                whisperEngine = it
                whisperPath = file.absolutePath
            }
        }
    }

    /**
     * Hands-free detector — ships inside the APK (2.3 MB), so it is available
     * whenever the build packed it; nothing to download.
     */
    @Volatile private var vadEngine: SileroVad? = null

    private fun sileroVad(): SileroVad? {
        if (!SileroVad.isAvailable(appContext)) return null
        vadEngine?.let { return it }
        synchronized(this) {
            vadEngine?.let { return it }
            return SileroVad(appContext).also { vadEngine = it }
        }
    }

    val hasHandsFreeMic: Boolean get() = SileroVad.isAvailable(appContext) && bundledAsrFile != null

    /** Bundled pronunciation-coach model if installed (a downloadable pack). */
    val pronunciationModelFile: File?
        get() {
            val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
            for (base in bases) {
                val f = File(base, GOP_MODEL_PATH)
                if (f.exists() && f.length() > 0L) return f
            }
            return null
        }

    val hasPronunciationCoach: Boolean get() = pronunciationModelFile != null

    @Volatile private var gopEngine: Wav2Vec2GopEngine? = null
    @Volatile private var gopPath: String? = null

    private fun pronunciationScorer(): PronunciationScorer {
        val file = pronunciationModelFile ?: return FakePronunciationScorer()
        gopEngine?.takeIf { gopPath == file.absolutePath }?.let { return it }
        synchronized(this) {
            gopEngine?.takeIf { gopPath == file.absolutePath }?.let { return it }
            return Wav2Vec2GopEngine(file).also {
                gopEngine = it
                gopPath = file.absolutePath
            }
        }
    }

    private fun hebrewTtsEngine(): HebrewTtsEngine? {
        val (nikud, voice) = hebrewTtsFiles ?: return null
        val key = nikud.absolutePath + "|" + voice.absolutePath
        hebrewEngine?.takeIf { hebrewPath == key }?.let { return it }
        synchronized(this) {
            hebrewEngine?.takeIf { hebrewPath == key }?.let { return it }
            return HebrewTtsEngine(nikud, voice).also {
                hebrewEngine = it
                hebrewPath = key
            }
        }
    }

    /**
     * Force every engine to load NOW, instead of lazily on first use.
     *
     * Each engine otherwise pays its load cost on the first turn that needs
     * it — the LLM is multi-GB and the ONNX sessions are hundreds of MB, so a
     * cold first conversation stalls repeatedly. This is the "get it all over
     * with" path: progress shows in the status line and under the TukiStep
     * logcat tag, and any engine whose model is not installed is skipped
     * rather than treated as a failure.
     *
     * Safe to call more than once — every accessor below is memoised, so a
     * second call is a no-op that just re-reports.
     */
    /** Progress of [preloadAll], owned here because the work outlives any screen. */
    enum class PreloadState { IDLE, RUNNING, DONE }

    private val _preload = MutableStateFlow(PreloadState.IDLE)
    val preload: StateFlow<PreloadState> = _preload

    /** 0..1 across the five preload steps; drives the splash progress bar. */
    private val _preloadProgress = MutableStateFlow(0f)
    val preloadProgress: StateFlow<Float> = _preloadProgress

    @Volatile private var preloadJob: Job? = null

    fun preloadAll(): Job = synchronized(this) {
        // Idempotent: a second tap joins the running job rather than starting
        // a second multi-GB load next to the first.
        preloadJob?.takeIf { it.isActive }?.let { return it }
        _preload.value = PreloadState.RUNNING
        preloadInternal().also { job ->
            preloadJob = job
            job.invokeOnCompletion { _preload.value = PreloadState.DONE }
        }
    }

    private fun preloadInternal(): Job = appScope.launch(Dispatchers.IO) {
        _preloadProgress.value = 0f
        runCatching { sileroVad()?.warmUp() }
            .onFailure { Log.w(MEM_TAG, "preload: vad failed", it) }
        _preloadProgress.value = 1 / 5f
        runCatching { bundledTtsEngine()?.warmUp() }
            .onFailure { Log.w(MEM_TAG, "preload: tts failed", it) }
        _preloadProgress.value = 2 / 5f
        runCatching { bundledAsrEngine()?.warmUp() }
            .onFailure { Log.w(MEM_TAG, "preload: asr failed", it) }
        _preloadProgress.value = 3 / 5f
        runCatching { pronunciationScorer() }
            .onFailure { Log.w(MEM_TAG, "preload: scorer failed", it) }
        _preloadProgress.value = 4 / 5f
        // Biggest and slowest, so it goes last: by the time it lands the cheap
        // engines are already usable. Thanks to the memoised engine + the
        // idempotent load(), this is the SAME instance a session will use —
        // the whole point of preloading.
        runCatching { createLlmEngine().load(LlmModelSpec(modelId = "tutor-default")) }
            .onFailure { Log.w(MEM_TAG, "preload: llm failed", it) }
        _preloadProgress.value = 1f
        Log.i(MEM_TAG, "preload: done")
    }

    /**
     * Speaks one fixed English sentence through the real voice path.
     *
     * Diagnostic: it exercises phonemizer -> ONNX synthesis -> AudioTrack
     * without needing a model, a lesson, or a working microphone, so "is the
     * voice broken" can be answered in one tap. The per-sentence shape of the
     * waveform (peak/rms/zero-crossing) lands in logcat under TukiTts, next to
     * the reference values measured off-device.
     */
    fun testVoice(): Job = appScope.launch(Dispatchers.IO) { speakTestLine() }

    /**
     * "Just chat" room: one LLM plays both parrots; each speaks with its own
     * voice. Tuki uses the parent-picked voice; Kiki uses a contrasting one
     * (a male voice when Tuki's is female and vice versa), chosen from the
     * voices actually packaged so the picker bug class cannot recur here.
     */
    fun createChatRoom(): ChatRoom {
        val kokoro = bundledTtsEngine()
        val tts = TtsRouter(
            english = kokoro ?: PlatformTtsEngine(appContext),
            hebrew = hebrewTtsEngine(),
        )
        return ChatRoom(
            llm = createLlmEngine(),
            speak = { speaker, text ->
                val tuki = TukiVoices.byId(profile.current().parentSettings.voiceId)
                val voice = if (speaker == ChatSpeaker.KIKI) kikiVoiceFor(tuki) else tuki
                if (availableVoices.any { it.id == voice.id }) {
                    kokoro?.voiceAsset = voice.id
                }
                tts.speak(text, TutorLanguage.ENGLISH).collect { }
            },
        )
    }

    /** A voice that contrasts with Tuki's, from what this APK carries. */
    private fun kikiVoiceFor(tuki: TukiVoice): TukiVoice {
        val pool = availableVoices.filter { it.id != tuki.id }
        return pool.firstOrNull { it.gender != tuki.gender && it.accent == tuki.accent }
            ?: pool.firstOrNull { it.gender != tuki.gender }
            ?: pool.firstOrNull()
            ?: tuki
    }

    /** Re-applies the parent-picked voice; call when leaving the chat room,
     *  which sets voices per speaker. */
    fun reapplyChosenVoice() {
        appScope.launch { applyVoice(profile.current().parentSettings.voiceId) }
    }

    /**
     * Audio-visual reinforcement. App-wide rather than per-screen: a burst
     * fired as a lesson ends should finish over whatever comes next, and the
     * chime must not be re-synthesized once per screen.
     */
    val rewards = RewardBus()

    private val chime by lazy { RewardChime() }

    /**
     * Celebrate: particles on screen and one short consonant cue, together.
     *
     * The sound only plays when the picture does — a chime with no burst
     * behind it (because three were already in flight) is a noise the child
     * cannot account for.
     */
    fun celebrate(kind: RewardKind) {
        if (!rewards.celebrate(kind)) return
        appScope.launch(Dispatchers.IO) {
            runCatching { chime.play(kind) }
                .onFailure { Log.w(MEM_TAG, "reward chime failed: ${it.message}") }
        }
    }

    fun createOrchestrator(scope: CoroutineScope): TutorOrchestrator {
        val kokoro = bundledTtsEngine()
        // Honour the parent's choice for this session; the picker also applies
        // it live, this covers a fresh process.
        appScope.launch { applyVoice(profile.current().parentSettings.voiceId) }
        val hebrew = hebrewTtsEngine()
        // Warm the ENGLISH voice off the critical path — otherwise the first
        // spoken reply pays ONNX session load on top of LLM generation. The
        // Hebrew stack (~0.45 GB) stays lazy on purpose: it may go unused all
        // session, and on 8 GB devices eager-loading it next to the LLM is
        // RAM pressure for nothing; the first Hebrew line pays ~a second once.
        appScope.launch(Dispatchers.IO) {
            runCatching { kokoro?.warmUp() }
            // 2.3 MB — warming it costs nothing and keeps the first hands-free
            // turn from stalling on session creation.
            runCatching { sileroVad()?.warmUp() }
        }
        return TutorOrchestrator(
            llm = createLlmEngine(),
            asr = bundledAsrEngine() ?: PlatformAsrEngine(appContext),
            tts = TtsRouter(
                english = kokoro ?: PlatformTtsEngine(appContext),
                hebrew = hebrew,
            ),
            scorer = pronunciationScorer(),
            content = content,
            profile = profile,
            policy = ScriptedDialoguePolicy(),
            scope = scope,
            // Hebrew explanations ride on the quality tier only. E2B failed the
            // Hebrew eval gate (4.03) where E4B passed (4.45), and the pick is
            // sticky per process, so this reads the same answer all session.
            tierSpeaksHebrew = { modelTierLabel == HEBREW_CAPABLE_TIER },
        )
    }

    /**
     * The on-device model file if one is present, else null. Resolved FRESH on
     * every read (it's a filesystem stat, cheap) so a model that finishes
     * downloading — or lands via `adb push` — is picked up the next time a
     * conversation starts, without reinstalling or restarting the app. Search
     * order is quality-tier E4B then base-tier E2B, each under the app files dir
     * AND the external files dir (the easy `adb push` target,
     * docs/running-on-device.md).
     */
    val modelFile: File? get() = resolveModelFile()

    /**
     * True when a real `.litertlm` model is on device, so the tutor uses the real
     * LiteRT-LM engine (Gemma 4). False means the scripted demo engine — surfaced
     * in the UI so a tester always knows which mode they are in.
     */
    val usingRealLlm: Boolean get() = modelFile != null

    private fun resolveModelFile(): File? {
        val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
        for (base in bases) {
            for (name in MODEL_CANDIDATES) {
                val f = File(base, name)
                if (f.exists() && f.length() > 0L) return f
            }
        }
        return null
    }

    /**
     * Which tier the CURRENT session actually loaded ("E4B"/"E2B"), for the
     * badge — so a tester sees at a glance when the memory policy fell back.
     */
    @Volatile
    var modelTierLabel: String? = null
        private set

    /**
     * Real engine when a model is on device, scripted fake otherwise — the
     * single swap point. When BOTH tiers are installed (a Pixel 9 with the
     * E4B pack on top of the E2B base), [LlmTierPolicy] picks per session from
     * how much memory the device has free RIGHT NOW: E4B on a fresh device,
     * E2B after a day of apps — because on a 12 GB device the alternative to
     * falling back is the session dying mid-reply. The pick and its reason go
     * to logcat (`model_pick:`), and the badge shows the tier.
     */
    /** versionCode + install time: changes on every reinstall, so a new APK
     *  always re-tests the GPU path instead of inheriting an old verdict. */
    private fun installStamp(): String = runCatching {
        val pi = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${BuildConfig.VERSION_CODE}@${pi.lastUpdateTime}"
    }.getOrDefault(BuildConfig.VERSION_CODE.toString())

    // The LLM engine singleton — one instance, decided ONCE per process.
    // The container is the app-wide singleton, so holding it here IS the
    // singleton. The tier pick (E4B vs E2B) runs only while no engine exists,
    // because that is the only moment the memory reading is honest: re-picking
    // per call measured free memory AFTER our own multi-GB model had occupied
    // it — the launch preload loaded E4B on GPU, four seconds later a session
    // repicked, read the now-lower availMem, chose E2B, and threw the warm
    // E4B away (both GPU loads paid; device log 2026-08-27 00:45). The pick
    // re-runs only if the cached model was uninstalled.
    @Volatile private var llmEngine: LlmEngine? = null
    @Volatile private var llmEnginePath: String? = null
    @Volatile private var llmEngineTierLabel: String? = null
    private val llmLock = Any()

    private fun createLlmEngine(): LlmEngine {
        val installed = LinkedHashMap<String, File>() // preference order kept
        val bases = listOfNotNull(appContext.filesDir, appContext.getExternalFilesDir(null))
        for (name in MODEL_CANDIDATES) {
            for (base in bases) {
                val f = File(base, name)
                if (f.exists() && f.length() > 0L) {
                    installed.putIfAbsent(name, f)
                    break
                }
            }
        }
        synchronized(llmLock) {
            if (installed.isEmpty()) {
                modelTierLabel = null
                // Models were deleted out from under a cached engine: let it go.
                llmEngine?.let { old -> appScope.launch { runCatching { old.unload() } } }
                llmEngine = null
                llmEnginePath = null
                llmEngineTierLabel = null
                return FakeLlmEngine()
            }
            llmEngine?.let { cached ->
                val path = llmEnginePath
                if (path != null && installed.values.any { it.absolutePath == path }) {
                    modelTierLabel = llmEngineTierLabel
                    return cached
                }
                appScope.launch { runCatching { cached.unload() } }
                llmEngine = null
                llmEnginePath = null
            }
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val choice = checkNotNull(LlmTierPolicy.choose(installed.keys.toList(), info.availMem / 1e9f))
            modelTierLabel = choice.tierLabel
            llmEngineTierLabel = choice.tierLabel
            Log.i(
                "TukiLlm",
                "model_pick: ${choice.reason}${if (choice.tight) " [TIGHT]" else ""} " +
                    "(sticky for this process)",
            )
            val path = installed.getValue(choice.path).absolutePath
            val compileCache = File(appContext.cacheDir, "litertlm-compile-cache")
                .apply { mkdirs() }.absolutePath
            return LiteRtLmEngine(path, installStamp(), compileCache).also {
                llmEngine = it
                llmEnginePath = path
            }
        }
    }

    companion object {
        // Preference order: quality-tier E4B first, then base-tier E2B.
        private val MODEL_CANDIDATES = listOf(
            "models/gemma-4-E4B-it.litertlm",
            "models/gemma-4-E2B-it.litertlm",
        )

        // Bundled ASR, best first. The short-window ACFT export transcribes the
        // one-sentence answers children actually give ~12x faster than the 30 s
        // medium export at the same accuracy, in less than half the file size
        // (docs/asr-model-eval.md); the 30 s exports stay recognized so a
        // device that already has one keeps working after an app update.
        private val ASR_CANDIDATES = listOf(
            "models/acft_whisper_small.en_10s.tflite",
            "models/whisper_large_v3_turbo_30s_i4.tflite",
            "models/whisper_medium_30s_i4.tflite",
        )

        // Bundled Kokoro voice (single q8f16 ONNX build; the HF file name is kept
        // so downloads, imports and the catalog all agree on one name).
        private val TTS_CANDIDATES = listOf(
            "models/model_quantized.onnx",
        )

        // Bundled Hebrew voice (Phonikud stack), HF file names kept as above.
        /** Covers a range of phonemes, and tells the tester what to expect. */
        const val VOICE_TEST_LINE = "Hello! I am Tuki. Can you hear me clearly?"

        private const val TTS_HE_NIKUD_PATH = "models/phonikud-1.0.int8.onnx"
        private const val TTS_HE_VOICE_PATH = "models/model.onnx"

        // Pronunciation coach (wav2vec2 IPA phoneme CTC, int8).
        private const val GOP_MODEL_PATH = "models/wav2vec2-phoneme-int8.onnx"

        private const val MEM_TAG = "TukiMem"

        /**
         * The only tier trusted to explain in Hebrew. Not a preference — the
         * eval harness scored E4B at 4.45 and E2B at 4.03 with a meta-AI flag
         * (eval/hebrew/results/VERDICT.md), and shipping the smaller model's
         * Hebrew would ship exactly what that eval rejected.
         */
        private const val HEBREW_CAPABLE_TIER = "E4B"

        // Process-wide singleton: pack-download state and appScope must survive
        // Activity recreation (rotation), which previously rebuilt everything.
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}

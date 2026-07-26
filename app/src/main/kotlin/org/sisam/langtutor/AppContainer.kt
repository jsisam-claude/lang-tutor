package org.sisam.langtutor

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.engine.HebrewTtsEngine
import org.sisam.langtutor.engine.KokoroTtsEngine
import org.sisam.langtutor.engine.LiteRtLmEngine
import org.sisam.langtutor.engine.PlatformAsrEngine
import org.sisam.langtutor.engine.PlatformTtsEngine
import org.sisam.langtutor.engine.TtsRouter
import org.sisam.langtutor.engine.WhisperAsrEngine
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.packs.HttpPackFetcher
import org.sisam.langtutor.packs.PackRepository
import org.sisam.langtutor.packs.RealPackRepository
import org.sisam.langtutor.packs.ResourceCatalogLoader
import org.sisam.langtutor.packs.ramTierGb
import org.sisam.langtutor.profile.JsonFileProfileStore
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.tutor.ScriptedDialoguePolicy
import org.sisam.langtutor.tutor.TutorOrchestrator

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
        runCatching { appContext.assets.open(KokoroTtsEngine.VOICE_ASSET).close() }.isSuccess
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
            return WhisperAsrEngine(file).also {
                whisperEngine = it
                whisperPath = file.absolutePath
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

    fun createOrchestrator(scope: CoroutineScope): TutorOrchestrator {
        val kokoro = bundledTtsEngine()
        val hebrew = hebrewTtsEngine()
        // Warm the voice sessions off the critical path — otherwise the FIRST
        // spoken reply pays the ONNX session load on top of LLM generation.
        appScope.launch(Dispatchers.IO) {
            runCatching { kokoro?.warmUp() }
            runCatching { hebrew?.warmUp() }
        }
        return TutorOrchestrator(
            llm = createLlmEngine(),
            asr = bundledAsrEngine() ?: PlatformAsrEngine(appContext),
            tts = TtsRouter(
                english = kokoro ?: PlatformTtsEngine(appContext),
                hebrew = hebrew,
            ),
            scorer = FakePronunciationScorer(),
            content = content,
            profile = profile,
            policy = ScriptedDialoguePolicy(),
            scope = scope,
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
     * Real engine when the model is on device, scripted fake otherwise — the
     * single swap point. Until the file exists the fake keeps the full tutoring
     * loop working. E4B is the quality-tier model that passes the Hebrew gate
     * (eval/hebrew/results/VERDICT.md); E2B is the base-install file.
     */
    private fun createLlmEngine(): LlmEngine =
        modelFile?.let { LiteRtLmEngine(it.absolutePath) } ?: FakeLlmEngine()

    companion object {
        // Preference order: quality-tier E4B first, then base-tier E2B.
        private val MODEL_CANDIDATES = listOf(
            "models/gemma-4-E4B-it.litertlm",
            "models/gemma-4-E2B-it.litertlm",
        )

        // Bundled Whisper ASR variants (turbo for 12/16 GB, medium for 8 GB).
        private val ASR_CANDIDATES = listOf(
            "models/whisper_large_v3_turbo_30s_i4.tflite",
            "models/whisper_medium_30s_i4.tflite",
        )

        // Bundled Kokoro voice (single q8f16 ONNX build; the HF file name is kept
        // so downloads, imports and the catalog all agree on one name).
        private val TTS_CANDIDATES = listOf(
            "models/model_q8f16.onnx",
        )

        // Bundled Hebrew voice (Phonikud stack), HF file names kept as above.
        private const val TTS_HE_NIKUD_PATH = "models/phonikud-1.0.int8.onnx"
        private const val TTS_HE_VOICE_PATH = "models/model.onnx"

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

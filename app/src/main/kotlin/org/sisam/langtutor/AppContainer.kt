package org.sisam.langtutor

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.engine.LiteRtLmEngine
import org.sisam.langtutor.engine.PlatformAsrEngine
import org.sisam.langtutor.engine.PlatformTtsEngine
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.llm.LlmEngine
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
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

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
    )

    fun createOrchestrator(scope: CoroutineScope): TutorOrchestrator = TutorOrchestrator(
        llm = createLlmEngine(),
        asr = PlatformAsrEngine(appContext),
        tts = PlatformTtsEngine(appContext),
        scorer = FakePronunciationScorer(),
        content = content,
        profile = profile,
        policy = ScriptedDialoguePolicy(),
        scope = scope,
    )

    /**
     * The on-device model file if one is present (resolved once at startup),
     * else null. Search order is quality-tier E4B then base-tier E2B, each under
     * the app files dir AND the external files dir — the latter is the easy
     * `adb push` target for a device bring-up (docs/running-on-device.md). In a
     * shipping build the file arrives via the install-time asset pack or a
     * user-approved download (see [packs]).
     */
    val modelFile: File? = resolveModelFile()

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

    private companion object {
        // Preference order: quality-tier E4B first, then base-tier E2B.
        val MODEL_CANDIDATES = listOf(
            "models/gemma-4-E4B-it.litertlm",
            "models/gemma-4-E2B-it.litertlm",
        )
    }
}

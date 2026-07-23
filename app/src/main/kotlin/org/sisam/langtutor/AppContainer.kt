package org.sisam.langtutor

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
import org.sisam.langtutor.packs.FakePackRepository
import org.sisam.langtutor.packs.PackRepository
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

    val content: ContentRepository = ResourceContentRepository()

    val profile: LearnerProfileStore =
        JsonFileProfileStore(File(context.filesDir, "profile.json").toPath())

    // User-approved enhancement downloads; fake here, real downloader later.
    val packs: PackRepository = FakePackRepository()

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
     * Real engine when the model is on device, scripted fake otherwise — the
     * single swap point. The model file is delivered by the install-time asset
     * pack or a user-approved download (see [packs]); until it exists the fake
     * keeps the full tutoring loop working. E4B is the quality-tier model that
     * passes the Hebrew gate (VERDICT.md); E2B would be the base-install file.
     */
    private fun createLlmEngine(): LlmEngine {
        val modelFile = QUALITY_MODEL_CANDIDATES
            .map { File(appContext.filesDir, it) }
            .firstOrNull { it.exists() }
        return if (modelFile != null) LiteRtLmEngine(modelFile.absolutePath) else FakeLlmEngine()
    }

    private companion object {
        // Preference order: quality-pack E4B first, then base-install E2B.
        val QUALITY_MODEL_CANDIDATES = listOf(
            "models/gemma-4-E4B-it.litertlm",
            "models/gemma-4-E2B-it.litertlm",
        )
    }
}

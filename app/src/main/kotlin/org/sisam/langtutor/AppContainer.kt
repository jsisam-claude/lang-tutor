package org.sisam.langtutor

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.engine.PlatformAsrEngine
import org.sisam.langtutor.engine.PlatformTtsEngine
import org.sisam.langtutor.llm.FakeLlmEngine
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
 * the LLM stays scripted until the bundled model engine (LiteRT-LM + model
 * pack) lands here.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val content: ContentRepository = ResourceContentRepository()

    val profile: LearnerProfileStore =
        JsonFileProfileStore(File(context.filesDir, "profile.json").toPath())

    // User-approved enhancement downloads; fake here, real downloader later.
    val packs: PackRepository = FakePackRepository()

    fun createOrchestrator(scope: CoroutineScope): TutorOrchestrator = TutorOrchestrator(
        llm = FakeLlmEngine(),
        asr = PlatformAsrEngine(appContext),
        tts = PlatformTtsEngine(appContext),
        scorer = FakePronunciationScorer(),
        content = content,
        profile = profile,
        policy = ScriptedDialoguePolicy(),
        scope = scope,
    )
}

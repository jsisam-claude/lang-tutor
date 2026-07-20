package org.sisam.langtutor

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.sisam.langtutor.content.ContentRepository
import org.sisam.langtutor.content.ResourceContentRepository
import org.sisam.langtutor.llm.FakeLlmEngine
import org.sisam.langtutor.packs.FakePackRepository
import org.sisam.langtutor.packs.PackRepository
import org.sisam.langtutor.profile.JsonFileProfileStore
import org.sisam.langtutor.profile.LearnerProfileStore
import org.sisam.langtutor.speech.FakeAsrEngine
import org.sisam.langtutor.speech.FakePronunciationScorer
import org.sisam.langtutor.speech.FakeTtsEngine
import org.sisam.langtutor.tutor.ScriptedDialoguePolicy
import org.sisam.langtutor.tutor.TutorOrchestrator

/**
 * Manual composition root. The scaffold wires FAKE engines so the whole app runs
 * with zero model weights; swapping in real engines (LiteRT-LM, sherpa-onnx,
 * Phonikud) later touches only this file.
 */
class AppContainer(context: Context) {

    val content: ContentRepository = ResourceContentRepository()

    val profile: LearnerProfileStore =
        JsonFileProfileStore(File(context.filesDir, "profile.json").toPath())

    // User-approved enhancement downloads; fake here, real downloader later.
    val packs: PackRepository = FakePackRepository()

    fun createOrchestrator(scope: CoroutineScope): TutorOrchestrator = TutorOrchestrator(
        llm = FakeLlmEngine(),
        asr = FakeAsrEngine(),
        tts = FakeTtsEngine(),
        scorer = FakePronunciationScorer(),
        content = content,
        profile = profile,
        policy = ScriptedDialoguePolicy(),
        scope = scope,
    )
}

package org.sisam.langtutor.tutor

import org.sisam.langtutor.content.CurriculumUnit

data class TurnContext(
    val childUtterance: String,
    val confidence: Float,
    val unit: CurriculumUnit?,
)

sealed interface TutorMove {

    /** Cheap retry path: no LLM call, just a warm scripted prompt via TTS. */
    data class AskRepeat(val prompt: String) : TutorMove

    /** Full path: build an LLM request from the utterance + lesson context. */
    data class RespondViaLlm(val instruction: String) : TutorMove
}

/** Decides the tutor's next move for a child turn. Deterministic v0. */
interface DialoguePolicy {
    fun nextMove(context: TurnContext): TutorMove
}

/**
 * P1 policy: low ASR confidence never reaches the LLM — the tutor asks the child
 * to try again (fast, cheap, and avoids "responding" to a misheard utterance).
 */
class ScriptedDialoguePolicy(
    private val confidenceThreshold: Float = 0.5f,
) : DialoguePolicy {

    override fun nextMove(context: TurnContext): TutorMove {
        if (context.confidence < confidenceThreshold) {
            return TutorMove.AskRepeat("Let's try that again! Say it nice and slow.")
        }
        val lessonTitle = context.unit?.title?.en ?: "the lesson"
        return TutorMove.RespondViaLlm(
            instruction = "The child is practicing \"$lessonTitle\". " +
                "Praise briefly, gently recast any mistake, then ask one short follow-up question.",
        )
    }
}

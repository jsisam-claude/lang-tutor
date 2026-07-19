package org.sisam.langtutor.tutor

sealed interface TutorTurnState {
    data object Idle : TutorTurnState
    data object Listening : TutorTurnState
    data object Transcribing : TutorTurnState

    /** LLM decode in progress; [partialReply] streams for the UI. */
    data class Thinking(val partialReply: String) : TutorTurnState
    data class Speaking(val text: String) : TutorTurnState

    /** Turn complete; optional [prompt] the tutor just asked. */
    data class AwaitingChild(val prompt: String?) : TutorTurnState
    data class Failed(val reason: String) : TutorTurnState
}

enum class Speaker { CHILD, TUTOR }

data class TranscriptEntry(
    val speaker: Speaker,
    val text: String,
    val confidence: Float? = null,
)

enum class TutorMode { SPEECH, TEXT }

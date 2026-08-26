package org.sisam.langtutor.tutor.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sisam.langtutor.llm.ChatMessage
import org.sisam.langtutor.llm.LlmEngine
import org.sisam.langtutor.llm.LlmEvent
import org.sisam.langtutor.llm.LlmModelSpec
import org.sisam.langtutor.llm.LlmRequest
import org.sisam.langtutor.llm.Role
import org.sisam.langtutor.safety.BlocklistSafetyFilter
import org.sisam.langtutor.safety.SafetyFilter

/** Who is talking in the room. */
enum class ChatSpeaker { CHILD, TUKI, KIKI }

data class ChatEntry(val speaker: ChatSpeaker, val text: String)

/**
 * "Just chat" — a freeform three-way room: the learner plus TWO parrots.
 *
 * Every learner message gets two replies in sequence: Tuki answers, then Kiki
 * reacts to BOTH the learner and Tuki — that second hop is what makes it read
 * as a conversation of three instead of two parallel chatbots. One LLM plays
 * both parts; each reply is its own generate call carrying the shared history
 * plus a "reply as X" instruction, and Kiki's budget is smaller because a
 * sidekick who talks as much as the lead is exhausting.
 *
 * Freeform does NOT mean unfiltered: every reply passes the same safety
 * filter as lesson replies before it is shown or spoken, and a blocked reply
 * drops the engine's cached context exactly like the lesson path does.
 *
 * Pure JVM; speech is injected as a lambda so the app can route each speaker
 * to a different voice.
 */
class ChatRoom(
    private val llm: LlmEngine,
    private val safety: SafetyFilter = BlocklistSafetyFilter(),
    private val speak: suspend (ChatSpeaker, String) -> Unit = { _, _ -> },
) {

    private val _messages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val messages: StateFlow<List<ChatEntry>> = _messages

    /** Which parrot is generating right now — drives the "typing…" bubble. */
    private val _typing = MutableStateFlow<ChatSpeaker?>(null)
    val typing: StateFlow<ChatSpeaker?> = _typing

    /** Which parrot is audibly speaking — drives the avatar animation. */
    private val _speaking = MutableStateFlow<ChatSpeaker?>(null)
    val speaking: StateFlow<ChatSpeaker?> = _speaking

    private var busy = false

    suspend fun start() {
        llm.load(LlmModelSpec(modelId = "chat"))
    }

    suspend fun shutdown() {
        llm.unload()
    }

    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || busy) return
        busy = true
        try {
            _messages.value += ChatEntry(ChatSpeaker.CHILD, clean)
            reply(ChatSpeaker.TUKI)
            reply(ChatSpeaker.KIKI)
        } finally {
            busy = false
        }
    }

    private suspend fun reply(speaker: ChatSpeaker) {
        _typing.value = speaker
        var out = ""
        try {
            llm.generate(buildRequest(speaker)).collect { event ->
                when (event) {
                    is LlmEvent.Token -> out += event.text
                    is LlmEvent.Done -> out = event.fullText
                }
            }
        } catch (e: Exception) {
            // A failed generation must not kill the room — the parrot just
            // says something safe and the conversation moves on.
            out = ""
        } finally {
            _typing.value = null
        }
        var final = sanitize(out)
        if (final.isEmpty() || !safety.check(final).allowed) {
            // The cached conversation holds what the model actually said.
            llm.invalidateContext()
            final = FALLBACK
        }
        _messages.value += ChatEntry(speaker, final)
        _speaking.value = speaker
        try {
            runCatching { speak(speaker, final) }
        } finally {
            _speaking.value = null
        }
    }

    /** The model sees named turns, so it sometimes echoes the name back. */
    private fun sanitize(raw: String): String {
        var t = raw.trim()
        for (name in listOf("Tuki:", "Kiki:", "TUKI:", "KIKI:")) {
            t = t.removePrefix(name).trim()
        }
        return t
    }

    private fun buildRequest(speaker: ChatSpeaker): LlmRequest {
        val history = _messages.value.takeLast(HISTORY_ENTRIES).map { entry ->
            when (entry.speaker) {
                ChatSpeaker.CHILD -> ChatMessage(Role.USER, entry.text)
                ChatSpeaker.TUKI -> ChatMessage(Role.ASSISTANT, "Tuki: ${entry.text}")
                ChatSpeaker.KIKI -> ChatMessage(Role.ASSISTANT, "Kiki: ${entry.text}")
            }
        }
        val who = if (speaker == ChatSpeaker.TUKI) TUKI_INSTRUCTION else KIKI_INSTRUCTION
        return LlmRequest(
            systemPrompt = ROOM_PROMPT,
            messages = listOf(ChatMessage(Role.SYSTEM, who)) + history,
            maxTokens = if (speaker == ChatSpeaker.TUKI) 64 else 40,
        )
    }

    companion object {
        const val FALLBACK = "Let's talk about something fun instead!"

        /** Both personas live in ONE stable system prompt; the per-call
         *  instruction only selects which one answers. */
        val ROOM_PROMPT = """
            You are two friendly parrots chatting in English with a Hebrew-speaking
            English learner. Tuki is warm and encouraging. Kiki is playful and
            curious. Keep every message to one or two short, simple sentences.
            Never write both parrots in one reply. Never discuss unsafe, scary,
            or grown-up subjects.
        """.trimIndent()

        const val TUKI_INSTRUCTION =
            "Reply as Tuki only. Respond to the learner's last message and keep the chat going."
        const val KIKI_INSTRUCTION =
            "Reply as Kiki only, reacting briefly to what the learner and Tuki just said. " +
                "Add one playful thought or question."

        const val HISTORY_ENTRIES = 18
    }
}

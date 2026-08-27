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
enum class ChatSpeaker { CHILD, TUKI }

data class ChatEntry(val speaker: ChatSpeaker, val text: String)

/**
 * "Just chat" — a freeform room: the learner and Tuki, one reply per message.
 *
 * It used to be a three-way room with a second parrot, Kiki, reacting to both
 * the learner and Tuki. That cost two generations and two synthesis passes per
 * learner message — roughly double the wait on a thermally clamped phone — and
 * bought a character the learner never speaks to. One parrot, one reply.
 *
 * The removal also un-broke the KV cache. Each reply used to carry a
 * per-speaker "reply as X" SYSTEM message, which the engine folds into the
 * conversation's system text; alternating it between Tuki and Kiki changed
 * that text every turn, so no conversation could ever be reused and every
 * reply re-prefilled the whole history. With one speaker the instruction is
 * constant and lives in [ROOM_PROMPT], and the transcript we send is a plain
 * growing suffix — which is exactly the shape `ConvoReuse` will reuse.
 *
 * Freeform does NOT mean unfiltered: every reply passes the same safety
 * filter as lesson replies before it is shown or spoken, and a blocked reply
 * drops the engine's cached context exactly like the lesson path does.
 *
 * Pure JVM; speech is injected as a lambda so the app can route the reply to
 * whichever voice the parent picked.
 */
class ChatRoom(
    private val llm: LlmEngine,
    private val safety: SafetyFilter = BlocklistSafetyFilter(),
    private val speak: suspend (ChatSpeaker, String) -> Unit = { _, _ -> },
) {

    private val _messages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val messages: StateFlow<List<ChatEntry>> = _messages

    /** Set while Tuki is generating — drives the "typing…" bubble. */
    private val _typing = MutableStateFlow<ChatSpeaker?>(null)
    val typing: StateFlow<ChatSpeaker?> = _typing

    /** Set while Tuki is audibly speaking — drives the avatar animation. */
    private val _speaking = MutableStateFlow<ChatSpeaker?>(null)
    val speaking: StateFlow<ChatSpeaker?> = _speaking

    /**
     * True from the child's message landing until the reply has finished
     * PLAYING. Observable because the UI must gate input on it: [send] drops
     * input while busy, and `typing` alone ends too early — it clears when
     * generation ends, well before the audio does, so a message sent during
     * Tuki's speech was silently lost after the input field already cleared.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    suspend fun start() {
        llm.load(LlmModelSpec(modelId = "chat"))
    }

    /**
     * Leave the room.
     *
     * The engine itself is NOT unloaded. Every room gets the SAME container-owned
     * engine, and a GPU load costs ~27 s on a Pixel 9 — 23 s of it compiling
     * OpenCL kernels — so dropping it on the way out of one room made the walk
     * back in cost half a minute. Releasing it is the container's job, on the
     * two signals that actually mean "done": the app going to background past
     * its grace period, and a system trim once the process is cached.
     */
    fun shutdown() = Unit

    suspend fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _busy.value) return
        _busy.value = true
        try {
            _messages.value += ChatEntry(ChatSpeaker.CHILD, clean)
            // Words first, then voice: the bubble appears the moment the model
            // is done, so the learner has something to read while the ~2 s of
            // synthesis runs instead of watching a still screen.
            val reply = compose()
            _messages.value += ChatEntry(ChatSpeaker.TUKI, reply)
            voice(reply)
        } finally {
            _busy.value = false
        }
    }

    /** Generate and vet Tuki's line. No side effects on the room. */
    private suspend fun compose(): String {
        _typing.value = ChatSpeaker.TUKI
        var out = ""
        try {
            llm.generate(buildRequest()).collect { event ->
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
        val final = sanitize(out)
        if (final.isEmpty() || !safety.check(final).allowed) {
            // The cached conversation holds what the model actually said.
            llm.invalidateContext()
            return FALLBACK
        }
        return final
    }

    private suspend fun voice(text: String) {
        _speaking.value = ChatSpeaker.TUKI
        try {
            runCatching { speak(ChatSpeaker.TUKI, text) }
        } finally {
            _speaking.value = null
        }
    }

    /** The prompt names him, so he sometimes signs his lines. */
    private fun sanitize(raw: String): String {
        var t = raw.trim()
        for (name in listOf("Tuki:", "TUKI:")) {
            t = t.removePrefix(name).trim()
        }
        return t
    }

    private fun buildRequest(): LlmRequest {
        // Verbatim text, no "Tuki: " prefix: the engine records each reply as
        // it was generated, and this window has to match it exactly or the
        // conversation gets rebuilt from scratch on every turn.
        val history = _messages.value.takeLast(HISTORY_ENTRIES).map { entry ->
            when (entry.speaker) {
                ChatSpeaker.CHILD -> ChatMessage(Role.USER, entry.text)
                ChatSpeaker.TUKI -> ChatMessage(Role.ASSISTANT, entry.text)
            }
        }
        return LlmRequest(
            systemPrompt = ROOM_PROMPT,
            messages = history,
            maxTokens = REPLY_TOKENS,
        )
    }

    companion object {
        const val FALLBACK = "Let's talk about something fun instead!"

        /** One stable system prompt, reused for the life of the room. */
        val ROOM_PROMPT = """
            You are Tuki, a friendly parrot chatting in English with a
            Hebrew-speaking English learner. You are warm, encouraging and
            curious. Reply to the learner's last message in one or two short,
            simple sentences, and usually end with a question that keeps the
            chat going. Never discuss unsafe, scary, or grown-up subjects.
        """.trimIndent()

        const val REPLY_TOKENS = 64

        const val HISTORY_ENTRIES = 18
    }
}

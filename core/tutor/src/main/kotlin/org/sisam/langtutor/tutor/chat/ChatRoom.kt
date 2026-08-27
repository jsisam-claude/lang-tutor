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
import org.sisam.langtutor.speech.HebrewText
import org.sisam.langtutor.safety.SafetyFilter

/** Who is talking in the room. */
enum class ChatSpeaker { CHILD, TUKI }

/**
 * One line in the room. [hebrew] is the Hebrew MEANING of [text] when the
 * learner asked for it and the model produced something usable — null
 * otherwise, and the bubble simply shows without it.
 */
data class ChatEntry(
    val speaker: ChatSpeaker,
    val text: String,
    val hebrew: String? = null,
)

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
    /**
     * Whether to ask for a Hebrew translation of each reply.
     *
     * Read per turn so the Parent Zone switch takes effect without leaving
     * the room. It costs no extra generation — the translation rides along in
     * the same reply behind [HEBREW_MARKER] — but it does cost a few tokens,
     * so a learner who does not want it does not pay for it.
     */
    private val wantsHebrew: () -> Boolean = { false },
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
            _messages.value += ChatEntry(ChatSpeaker.TUKI, reply.english, reply.hebrew)
            // Only the English is spoken. The translation is a reading aid;
            // hearing it would undercut the point of an English room.
            voice(reply.english)
        } finally {
            _busy.value = false
        }
    }

    /** What one turn produced: the spoken line, and its meaning if asked for. */
    internal data class Reply(val english: String, val hebrew: String?)

    /** Generate and vet Tuki's line. No side effects on the room. */
    private suspend fun compose(): Reply {
        val hebrewWanted = runCatching { wantsHebrew() }.getOrDefault(false)
        _typing.value = ChatSpeaker.TUKI
        var out = ""
        try {
            llm.generate(buildRequest(hebrewWanted)).collect { event ->
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
        val split = split(out)
        val final = sanitize(split.english)
        if (final.isEmpty() || !safety.check(final).allowed) {
            // The cached conversation holds what the model actually said.
            llm.invalidateContext()
            return Reply(FALLBACK, null)
        }
        return Reply(final, if (hebrewWanted) vetHebrew(split.hebrew) else null)
    }

    /**
     * Pull the translation off the end of the reply.
     *
     * One generation, not two. Splitting a marker off a single reply costs a
     * handful of tokens; a second call would cost a whole second decode per
     * turn, which is the thing this room just stopped doing when the second
     * parrot was removed.
     */
    private fun split(raw: String): Reply {
        val at = raw.indexOf(HEBREW_MARKER)
        if (at < 0) return Reply(raw, null)
        return Reply(
            english = raw.substring(0, at).trim(),
            // Only the first line after the marker: the model sometimes keeps
            // talking, and a paragraph of commentary is not a translation.
            hebrew = raw.substring(at + HEBREW_MARKER.length).trim().lineSequence()
                .firstOrNull { it.isNotBlank() }?.trim(),
        )
    }

    /**
     * The gauntlet for the Hebrew half.
     *
     * Model Hebrew is the weakest thing this app asks for — our own eval put
     * Gemma below the quality gate overall, passing only on translation
     * specifically (translate-scaffold 4.60 against a 4.0 bar), which is why
     * this one category is unlocked and the rest are not. Even so a wrong or
     * empty line must degrade to NO line, never to a confident mistranslation:
     * a learner cannot check this row, so it shows only when it is at least
     * structurally sound.
     */
    private fun vetHebrew(candidate: String?): String? {
        val t = candidate?.trim()?.trim('"', '\u201c', '\u201d')?.trim() ?: return null
        if (t.isEmpty() || t.length > MAX_HEBREW_CHARS) return null
        // It must actually be Hebrew, and mostly Hebrew — a reply that leaked
        // English into the translation slot is a failed translation.
        if (!HebrewText.contains(t)) return null
        val letters = t.count { it.isLetter() }
        val hebrewLetters = t.count { it in '\u0590'..'\u05ff' }
        if (letters == 0 || hebrewLetters * 2 < letters) return null
        if (!safety.check(t).allowed) return null
        return t
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

    private fun buildRequest(hebrewWanted: Boolean): LlmRequest {
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
            // Appended, not swapped: the whole point of one stable prompt is
            // that the conversation can be reused, and this text only changes
            // when the learner changes the setting.
            systemPrompt = if (hebrewWanted) ROOM_PROMPT + "\n\n" + HEBREW_INSTRUCTION else ROOM_PROMPT,
            messages = history,
            maxTokens = if (hebrewWanted) REPLY_TOKENS + HEBREW_TOKENS else REPLY_TOKENS,
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

        /** What the model writes before the translation. ASCII, so it cannot
         *  collide with either language's text. */
        const val HEBREW_MARKER = "HE:"

        val HEBREW_INSTRUCTION = """
            After your reply, on a new line, write exactly "HE:" followed by a
            natural Hebrew translation of what you just said. Translate the
            meaning, not word by word. Write nothing after that line.
        """.trimIndent()

        const val REPLY_TOKENS = 64

        /** Headroom for the translation so it cannot truncate the reply. */
        const val HEBREW_TOKENS = 64

        /** A translation of a two-sentence reply; anything longer is the model
         *  having started a new thought rather than translating. */
        const val MAX_HEBREW_CHARS = 300

        const val HISTORY_ENTRIES = 18
    }
}

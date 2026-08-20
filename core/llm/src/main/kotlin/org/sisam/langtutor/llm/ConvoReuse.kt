package org.sisam.langtutor.llm

/**
 * Whether a runtime conversation (with its KV cache) can absorb the next turn,
 * or must be rebuilt from the request's history.
 *
 * Why this exists: rebuilding every turn re-prefills the system prompt plus
 * the whole history window just to append one child utterance. On a CPU-decode
 * phone that is seconds of redundant compute per turn, growing with the
 * conversation. Reusing the conversation prefills only the new turn.
 *
 * Reuse is sound only when the new request is a genuine CONTINUATION of what
 * the conversation already holds. Comparing message COUNTS is not enough, and
 * the gap is not theoretical: a low-confidence turn takes the scripted
 * "say it again" branch, which appends to the transcript WITHOUT calling the
 * model. Counts still line up, so a count-based check would happily reuse —
 * and that child utterance would be silently missing from the model's context
 * for the rest of the session.
 *
 * So we compare content. The conversation holds every message it has ever
 * processed ([ConvoState.seen]); the caller passes a trailing window of the
 * transcript. Reuse is safe exactly when that window is a SUFFIX of what the
 * conversation saw — same messages, same order, ending at the same place.
 * Anything else (a skipped turn, a substituted reply, a lesson switch, a new
 * session) fails the check and rebuilds, which is simply the old behaviour.
 *
 * Pure JVM so the decision is unit-tested; the engine supplies the state.
 */
data class ConvoState(
    val systemText: String,
    val temperature: Float,
    /** Every message this conversation has processed, in order. */
    val seen: List<String>,
    val estTokens: Int,
    val dirty: Boolean,
)

object ConvoReuse {

    /** Keep well under the runtime's context window; rebuild past this. */
    const val MAX_EST_TOKENS = 3_072

    fun canReuse(
        state: ConvoState?,
        systemText: String,
        temperature: Float,
        prior: List<String>,
        maxEstTokens: Int = MAX_EST_TOKENS,
    ): Boolean = state != null &&
        !state.dirty &&
        state.systemText == systemText &&
        state.temperature == temperature &&
        state.estTokens < maxEstTokens &&
        isSuffix(prior, state.seen)

    /** True when [window] is exactly the trailing slice of [whole]. */
    internal fun isSuffix(window: List<String>, whole: List<String>): Boolean {
        if (window.size > whole.size) return false
        val offset = whole.size - window.size
        for (i in window.indices) if (window[i] != whole[offset + i]) return false
        return true
    }
}

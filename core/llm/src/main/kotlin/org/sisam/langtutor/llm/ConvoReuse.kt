package org.sisam.langtutor.llm

/**
 * Whether a runtime conversation (with its KV cache) can absorb the next turn,
 * or must be rebuilt from the request's history.
 *
 * Why this exists: rebuilding every turn re-prefills the system prompt plus
 * the whole history window just to append one child utterance. On the Pixel 9
 * the quality model decodes on CPU, so that is seconds of redundant compute
 * per turn, growing with the conversation. Reusing the conversation prefills
 * only the new turn.
 *
 * Reuse is only sound when the new request is a CONTINUATION of what the
 * conversation already holds:
 *  - same system text and temperature (both are fixed at creation);
 *  - at least as many prior messages as last time (the orchestrator only
 *    appends; a shrunken count means a new session or a reset);
 *  - the cache hasn't grown past [maxEstTokens] (rebuilding from the request's
 *    sliding window is the context-overflow escape hatch);
 *  - the previous stream finished cleanly ([ConvoState.dirty] false) — after a
 *    cut or error the conversation's internal history is unknowable.
 *
 * Pure JVM so the decision is unit-tested; the Android engine supplies the
 * state and acts on the verdict.
 */
data class ConvoState(
    val systemText: String,
    val temperature: Float,
    val priorCount: Int,
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
        priorCount: Int,
        maxEstTokens: Int = MAX_EST_TOKENS,
    ): Boolean = state != null &&
        !state.dirty &&
        state.systemText == systemText &&
        state.temperature == temperature &&
        priorCount >= state.priorCount &&
        state.estTokens < maxEstTokens
}

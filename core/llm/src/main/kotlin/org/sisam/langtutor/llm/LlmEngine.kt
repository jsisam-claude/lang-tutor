package org.sisam.langtutor.llm

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

data class GenerationStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val decodeTokensPerSecond: Float,
)

/**
 * Identifies which model to load. [assetPath] points into the model asset pack
 * in production (e.g. "models/gemma4-e2b.litertlm"); fakes ignore it.
 */
data class LlmModelSpec(
    val modelId: String,
    val assetPath: String? = null,
)

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val stopTokens: List<String> = emptyList(),
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
)

sealed interface LlmEvent {
    data class Token(val text: String) : LlmEvent
    data class Done(val fullText: String, val stats: GenerationStats) : LlmEvent
}

/**
 * On-device LLM behind a lifecycle: engines are loaded for the duration of a
 * tutoring session and unloaded afterwards (thermal/battery budget — see
 * docs/feasibility.md §3). Production implementation targets Gemma 4 E2B via
 * LiteRT-LM; [generate] is a cold flow — collection starts inference.
 */
interface LlmEngine {
    suspend fun load(spec: LlmModelSpec)
    fun generate(request: LlmRequest): Flow<LlmEvent>
    suspend fun unload()

    /**
     * Drop any cached conversation context, so the next [generate] rebuilds it
     * from the request alone.
     *
     * An engine may keep a live conversation across turns to avoid re-prefilling
     * the history (a large latency win on CPU-decode phones). That cache holds
     * what the MODEL produced, which is not always what the child was shown: a
     * reply the safety filter rejects is swapped downstream, but the rejected
     * text is still sitting in the cache conditioning every later turn. The
     * caller must be able to say "forget that".
     *
     * Default: no-op, for engines that hold no context.
     */
    fun invalidateContext() = Unit
}

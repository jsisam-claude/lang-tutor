package org.sisam.langtutor.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scripted engine so the app and tests run a full tutoring turn with no weights.
 * Streams each reply word-by-word to exercise streaming UI paths, and records
 * every request for test assertions.
 */
class FakeLlmEngine(
    private val script: List<String> = DEFAULT_SCRIPT,
) : LlmEngine {

    val calls = mutableListOf<LlmRequest>()
    var loaded = false
        private set

    private var scriptIndex = 0

    override suspend fun load(spec: LlmModelSpec) {
        loaded = true
    }

    override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
        calls += request
        val reply = script[scriptIndex % script.size]
        scriptIndex++
        val words = reply.split(" ")
        for (word in words) {
            delay(TOKEN_DELAY_MS)
            emit(LlmEvent.Token("$word "))
        }
        emit(
            LlmEvent.Done(
                fullText = reply,
                stats = GenerationStats(
                    promptTokens = request.systemPrompt.length / 4,
                    completionTokens = words.size,
                    decodeTokensPerSecond = 15f,
                ),
            ),
        )
    }

    override suspend fun unload() {
        loaded = false
    }

    companion object {
        private const val TOKEN_DELAY_MS = 40L
        val DEFAULT_SCRIPT = listOf(
            "Great job, Noa! 🎉 What color is the ball?",
            "Wonderful! Can you say: the bear is blue?",
            "You did it! Let's learn a new word.",
        )
    }
}

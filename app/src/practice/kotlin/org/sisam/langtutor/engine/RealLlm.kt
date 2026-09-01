package org.sisam.langtutor.engine

import org.sisam.langtutor.llm.LlmEngine

/**
 * The practice flavor carries no language model and no runtime for one
 * (docs/practice-flavor.md). The container checks BuildConfig.HAS_LLM before
 * it could ever get here; this exists so the same container compiles in both
 * flavors without naming a class that only the full flavor has.
 */
object RealLlm {
    @Suppress("UNUSED_PARAMETER")
    fun create(modelPath: String, installStamp: String, cacheDir: String, npuOptIn: () -> Boolean): LlmEngine =
        error("the practice flavor carries no language model")
}

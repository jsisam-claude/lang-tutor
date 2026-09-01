package org.sisam.langtutor.engine

import org.sisam.langtutor.llm.LlmEngine

/**
 * The full flavor's language model: the LiteRT-LM engine over the .litertlm
 * pack. The practice flavor has an object of the same name that refuses, so
 * the container never names the engine class — which does not exist there.
 */
object RealLlm {
    fun create(modelPath: String, installStamp: String, cacheDir: String, npuOptIn: () -> Boolean): LlmEngine =
        LiteRtLmEngine(modelPath = modelPath, installStamp = installStamp, cacheDir = cacheDir, npuOptIn = npuOptIn)
}

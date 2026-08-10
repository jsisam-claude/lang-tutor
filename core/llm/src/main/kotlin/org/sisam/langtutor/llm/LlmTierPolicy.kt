package org.sisam.langtutor.llm

/** What the tier policy decided, with the reason spelled out for the logs. */
data class ModelChoice(
    val path: String,
    /** "E4B", "E2B", or the file name for models the policy doesn't know. */
    val tierLabel: String,
    /** Human-readable why, with the numbers in it. Log this verbatim. */
    val reason: String,
    /** True when even the chosen model is below its comfort bar. */
    val tight: Boolean,
)

/**
 * Picks WHICH installed model to load for this session, from how much memory
 * the device has free right now.
 *
 * Written for the Pixel 9 (12 GB): it is the tier the plan gives the E4B
 * quality model, and also the tier with the least room for it. E4B's working
 * set plus the speech stack fits a freshly-booted 12 GB device, but after a
 * day of apps the same load is what gets the process killed mid-reply — the
 * failure testers see as "first reply never arrives". Preferring E2B in that
 * moment keeps the tutor alive at a quality cost; a dead app has no quality.
 *
 * The bars are working-set estimates, not measurements — deliberately
 * conservative, and DEVICE-VERIFY: the Pixel 9 bench (TukiStep capture plus
 * whether the app survives a session) is what calibrates them. Unknown model
 * names get a zero bar: the policy only vetoes models it has numbers for.
 */
object LlmTierPolicy {

    /** Rough working set: weights held by the runtime + KV cache + activations. */
    fun neededGb(path: String): Float = when {
        path.contains("E4B", ignoreCase = true) -> 4.5f
        path.contains("E2B", ignoreCase = true) -> 3.0f
        else -> 0f
    }

    fun tierLabel(path: String): String = when {
        path.contains("E4B", ignoreCase = true) -> "E4B"
        path.contains("E2B", ignoreCase = true) -> "E2B"
        else -> path.substringAfterLast('/')
    }

    /**
     * @param installed candidate paths in preference order (best first).
     * @param availGb the system's available-memory estimate right now.
     * @return the model to load, or null when nothing is installed.
     */
    fun choose(installed: List<String>, availGb: Float): ModelChoice? {
        if (installed.isEmpty()) return null

        for (path in installed) {
            val need = neededGb(path)
            if (availGb >= need) {
                val label = tierLabel(path)
                val skipped = installed.takeWhile { it != path }
                val why = if (skipped.isEmpty()) {
                    "picked $label: %.1f GB available ≥ %.1f GB bar".fmt(availGb, need)
                } else {
                    "picked $label: %.1f GB available is under the %.1f GB bar for %s"
                        .fmt(availGb, neededGb(skipped.first()), tierLabel(skipped.first()))
                }
                return ModelChoice(path, label, why, tight = false)
            }
        }

        // Nothing meets its bar. A dead session helps nobody: load the smallest
        // installed model anyway and say so, so the logs explain any kill.
        val smallest = installed.minByOrNull { neededGb(it) } ?: installed.first()
        return ModelChoice(
            path = smallest,
            tierLabel = tierLabel(smallest),
            reason = ("memory tight: %.1f GB available is under every bar — " +
                "loading ${tierLabel(smallest)} (%.1f GB bar) and hoping")
                .fmt(availGb, neededGb(smallest)),
            tight = true,
        )
    }

    private fun String.fmt(vararg args: Any) = format(java.util.Locale.US, *args)
}

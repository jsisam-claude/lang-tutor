package org.sisam.langtutor.tutor

/**
 * Shrink what Tuki says as the phone heats up.
 *
 * Round time scales almost linearly with the reply's token budget twice over:
 * the decode pays per token, and the synthesis pays per resulting character —
 * and BOTH rates degrade together when the SoC throttles (measured TTS RTF
 * 0.94 cool against 1.7–2.3 throttled on a Pixel 9, with decode slowing in
 * step). A budget that is right for a cool phone therefore balloons a hot
 * round at both ends. Saying less is the one lever that shortens both at
 * once, and pedagogy tolerates it: a shorter reply is usually the better
 * reply here anyway.
 *
 * The input is Android's thermal headroom forecast, where **1.0 means the
 * throttling threshold** (see `Thermal` in :app). Pure JVM so the policy is
 * unit-tested; the caller injects the reading.
 */
object ReplyBudget {

    /** Above this the platform's forecast says throttling is close. */
    const val WARM_HEADROOM = 0.80f

    /** At or past 1.0 the device IS throttling. */
    const val HOT_HEADROOM = 1.0f

    const val WARM_SCALE = 0.75f
    const val HOT_SCALE = 0.5f

    /** Never below a usable turn: praise + one short question. */
    const val FLOOR_TOKENS = 32

    /**
     * [tokens] scaled by how hot the device is. An unknown reading (NaN — the
     * platform rate-limits the call and sometimes declines) means no change:
     * guessing hot would quietly shorten every reply on devices that simply
     * do not report.
     */
    fun scaled(tokens: Int, headroom: Float): Int {
        val scale = when {
            headroom.isNaN() -> 1f
            headroom >= HOT_HEADROOM -> HOT_SCALE
            headroom >= WARM_HEADROOM -> WARM_SCALE
            else -> 1f
        }
        if (scale >= 1f) return tokens
        return (tokens * scale).toInt().coerceAtLeast(minOf(tokens, FLOOR_TOKENS))
    }
}

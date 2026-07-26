package org.sisam.langtutor.packs

import kotlin.math.roundToInt

/**
 * Maps a device's total memory in bytes (ActivityManager.MemoryInfo.totalMem)
 * to its marketing RAM tier in GB — the value fed to [PackRepository.eligiblePacks].
 *
 * totalMem reports WELL below physical RAM (kernel + GPU/carveout reserves eat
 * 4–10%): an 8 GB Pixel 9a can report ~7.4 GB, a 12 GB Pixel 9 ~11.1 GB, a
 * 16 GB Pro ~15.1 GB. Naive rounding therefore under-tiers real devices (11.1
 * would become "11" and lose the 12 GB-gated model), so we map via thresholds
 * that sit safely below each marketing tier.
 */
fun ramTierGb(totalMemBytes: Long): Int {
    val gb = totalMemBytes / 1_000_000_000.0
    return when {
        gb >= 14.0 -> 16
        gb >= 10.5 -> 12
        gb >= 7.0 -> 8
        gb >= 5.2 -> 6
        else -> gb.roundToInt()
    }
}

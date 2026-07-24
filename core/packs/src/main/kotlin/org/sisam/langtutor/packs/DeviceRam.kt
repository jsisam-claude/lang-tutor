package org.sisam.langtutor.packs

import kotlin.math.roundToInt

/**
 * Maps a device's total memory in bytes (e.g. ActivityManager.MemoryInfo.totalMem)
 * to its marketing RAM tier in GB — the value fed to [PackRepository.eligiblePacks].
 *
 * Reported totalMem sits a bit below physical RAM (the kernel/GPU reserve some),
 * so dividing by 10^9 (decimal GB) lands on the round marketing number: an 8 GB
 * phone reports ~8.2, a 12 GB one ~12.0, a 16 GB one ~16.3. Rounding recovers the
 * tier the RAM gate is written against (Pixel 9a = 8, Pixel 9 = 12, Pro = 16).
 */
fun ramTierGb(totalMemBytes: Long): Int =
    (totalMemBytes / 1_000_000_000.0).roundToInt()

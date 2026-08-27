package org.sisam.langtutor.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * How close this phone is to throttling — the context every slow number needs.
 *
 * A Pixel 9 log from 2026-08-27 showed identical TTS work taking 2126 ms and
 * then 3912 ms four minutes later, and nothing in our own logs explained it.
 * The answer only came from `adb shell dumpsys thermalservice`: the Cortex-X4
 * at 90 C with the skin at a comfortable 39 C, so the device felt fine while
 * the cores were being clamped. Slow numbers with no thermal context beside
 * them are unreadable, and that is a logging bug as much as a perf one.
 *
 * [headroom] is the single most useful number here: Android forecasts it as a
 * ratio where **1.0 means the throttling threshold**. Below ~0.8 the readings
 * around it are trustworthy; approaching 1.0 they are not, and a benchmark
 * taken there is measuring the weather.
 */
object Thermal {

    private const val TAG = "TukiThermal"

    @Volatile private var power: PowerManager? = null

    /** Current status, or -1 before [start] or where unavailable. */
    val status: Int get() = runCatching { power?.currentThermalStatus ?: -1 }.getOrDefault(-1)

    /**
     * Forecast headroom: 1.0 is the throttling point, above 1.0 is throttled.
     * NaN when the platform declines to answer (it rate-limits the call).
     */
    val headroom: Float
        get() = runCatching { power?.getThermalHeadroom(0) ?: Float.NaN }.getOrDefault(Float.NaN)

    /** Short suffix for another log line, empty while the device is cool. */
    fun suffix(): String {
        val s = status
        if (s <= PowerManager.THERMAL_STATUS_NONE) return ""
        val hr = headroom
        val hrText = if (hr.isNaN()) "" else " hr=%.2f".format(hr)
        return " [thermal ${label(s)}$hrText]"
    }

    fun label(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    /** Begin watching. Transitions are logged, so a run that got slower has a
     *  timestamped reason in the same log rather than in a separate dumpsys. */
    fun start(context: Context) {
        if (power != null) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        power = pm
        runCatching {
            pm.addThermalStatusListener { status ->
                Log.w(TAG, "thermal status -> ${label(status)} (headroom ${"%.2f".format(headroom)})")
            }
        }.onFailure { Log.w(TAG, "thermal listener unavailable: ${it.message}") }
        Log.i(TAG, "watching; now ${label(pm.currentThermalStatus)} (headroom ${"%.2f".format(headroom)})")
    }
}

package org.sisam.langtutor.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.R
import org.sisam.langtutor.engine.EngineStatus

/**
 * "Tuki is …" while an engine is busy. Silent when nothing slow is running.
 *
 * Models here load lazily and weigh hundreds of megabytes, so the first mic
 * press or first Hebrew line can sit for tens of seconds. A spinner alone
 * doesn't distinguish "working" from "stuck", so this also counts the seconds
 * out loud once a step passes [COUNTER_AFTER_SECONDS] — visible progress is
 * what keeps a child (and a tester) from concluding the app died.
 *
 * Debug builds additionally show the technical detail behind the friendly line,
 * matching how the screen already surfaces failure reasons.
 */
@Composable
fun EngineStatusLine(modifier: Modifier = Modifier) {
    val active by EngineStatus.current.collectAsState()
    val step = active
    if (step != null) {
        var seconds by remember(step) { mutableIntStateOf(0) }
        LaunchedEffect(step) {
            while (true) {
                delay(1_000)
                seconds = ((System.currentTimeMillis() - step.startedAtMillis) / 1_000).toInt()
            }
        }

        val label = stringResource(step.kind.labelRes())
        val suffix = stringResource(R.string.seconds_suffix)
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Column {
                Text(
                    text = if (seconds >= COUNTER_AFTER_SECONDS) "$label ($seconds$suffix)" else label,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (BuildConfig.DEBUG && step.detail.isNotEmpty()) {
                    Text(
                        text = "${step.kind} · ${step.detail}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** Seconds before the counter appears — short steps shouldn't flash a number. */
private const val COUNTER_AFTER_SECONDS = 2

@StringRes
private fun EngineStatus.Kind.labelRes(): Int = when (this) {
    EngineStatus.Kind.LLM_LOAD -> R.string.step_llm_load
    EngineStatus.Kind.LLM_GENERATE -> R.string.step_llm_generate
    EngineStatus.Kind.ASR_LOAD -> R.string.step_asr_load
    EngineStatus.Kind.ASR_RUN -> R.string.step_asr_run
    EngineStatus.Kind.TTS_LOAD -> R.string.step_tts_load
    EngineStatus.Kind.TTS_RUN -> R.string.step_tts_run
    EngineStatus.Kind.HEBREW_LOAD -> R.string.step_hebrew_load
    EngineStatus.Kind.HEBREW_RUN -> R.string.step_hebrew_run
    EngineStatus.Kind.COACH_LOAD -> R.string.step_coach_load
    EngineStatus.Kind.COACH_RUN -> R.string.step_coach_run
    EngineStatus.Kind.VAD_LOAD -> R.string.step_vad_load
}

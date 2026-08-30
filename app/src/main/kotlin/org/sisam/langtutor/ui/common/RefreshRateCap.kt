package org.sisam.langtutor.ui.common

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.engine.EngineStatus
import org.sisam.langtutor.profile.LearnerProfile

/**
 * The 60 Hz decode experiment (docs/latency.md scorecard): UI composition
 * shares the GPU with LLM decode, so halving the frames MIGHT buy tokens.
 * While the experimental switch is on and a generation is running, ask the
 * window for a 60 Hz refresh; ask for "no preference" the moment it ends.
 *
 * `preferredRefreshRate` is a HINT — the panel's mode switch is up to the
 * system — which is exactly the right strength for an A/B: nothing here can
 * break rendering, and the measurement is the tokens-per-second line in
 * logcat with the switch on versus off.
 */
@Composable
fun RefreshRateCapEffect(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val enabled = profile.parentSettings.capRefreshDuringDecode
    val generating by EngineStatus.generating.collectAsState()
    val view = LocalView.current
    LaunchedEffect(enabled, generating) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val attributes = window.attributes
        val target = if (enabled && generating) 60f else 0f
        if (attributes.preferredRefreshRate != target) {
            attributes.preferredRefreshRate = target
            window.attributes = attributes
        }
    }
}

package org.sisam.langtutor.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R

/**
 * Launch screen: Tuki, the title, and an honest progress bar.
 *
 * Shown for a fixed beat while [AppContainer.preloadAll] runs underneath — the
 * bar tracks the real five preload steps, not a fake sweep. The screen never
 * BLOCKS on loading: after the beat the app proceeds and the heavy tail (the
 * LLM) keeps loading in the background, which is the whole point — the splash
 * buys the loaders a head start, it is not a gate.
 */
@Composable
fun SplashScreen(container: AppContainer) {
    val progress by container.preloadProgress.collectAsState()
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 400),
        label = "splash-progress",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // speaking=true so the bird is alive on the very first screen — the
        // beak/bob loop doubles as a "we're doing something" signal.
        TukiParrot(speaking = true, size = 140.dp)
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.splash_tagline),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

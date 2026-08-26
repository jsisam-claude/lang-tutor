package org.sisam.langtutor.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R

/**
 * Launch screen: Tuki on a branch under a drifting sky, the title, and an
 * honest progress bar.
 *
 * Shown for a fixed beat while [AppContainer.preloadAll] runs underneath — the
 * bar tracks the real five preload steps, not a fake sweep. The screen never
 * BLOCKS on loading: after the beat the app proceeds and the heavy tail (the
 * LLM) keeps loading in the background, which is the whole point — the splash
 * buys the loaders a head start, it is not a gate.
 *
 * The scenery earns its four seconds: a still logo makes a wait feel like a
 * freeze, and this is the one screen where nothing else is competing for
 * attention. It is also the first thing an accessibility-configured device
 * shows, so the whole stack scrolls and the art yields first.
 */
@Composable
fun SplashScreen(container: AppContainer) {
    val progress by container.preloadProgress.collectAsState()
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 400),
        label = "splash-progress",
    )
    val parrot = A11y.decorativeDp(comfortable = 140, minimum = 84)

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackdrop(modifier = Modifier.fillMaxSize())
        TwinkleField(modifier = Modifier.fillMaxSize(), count = 16)

        // Centre when it fits, scroll when it doesn't: at 2.0x font the title
        // and tagline alone can outgrow a short viewport, and a splash that
        // clips its own progress bar is worse than one that scrolls.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = A11y.gutter, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // speaking=true so the bird is alive on the very first screen —
                // the beak/bob loop doubles as a "we're doing something" signal.
                TukiParrot(speaking = true, size = parrot)
                // The perch is pure scene-setting, so it is the first thing to
                // go when the layout is under pressure.
                if (!A11y.cramped) {
                    Perch(
                        modifier = Modifier
                            .size(width = parrot * 1.15f, height = parrot * 0.22f)
                            .padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = if (A11y.hugeText) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.displaySmall
                    },
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.splash_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .fillMaxWidth()
                        // Rounded ends and a little weight: a hairline bar
                        // reads as a divider, not as progress.
                        .widthIn(max = 420.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}

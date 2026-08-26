package org.sisam.langtutor.ui.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.UnitSummary
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.TukiParrot
import androidx.compose.ui.Alignment

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenLesson: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenParent: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val units by produceState<List<UnitSummary>>(initialValue = emptyList(), container) {
        value = container.content.listUnits()
    }
    val profile by container.profile.profile.collectAsState(
        initial = org.sisam.langtutor.profile.LearnerProfile.EMPTY,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrollable: the content is taller than the screen (ten unit
            // cards on Home, the pack list in Parent Zone), and an unscrolled
            // Column silently CLIPS its tail. That hid the Parent Zone button
            // — the only route to installing models — off the bottom edge.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.home_xp, profile.xp),
            style = MaterialTheme.typography.bodyLarge,
        )
        // The container warms the voice and the mic detector in the background
        // right after launch; this is where that work becomes visible.
        EngineStatusLine()

        units.forEach { unit ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = unit.title.he, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${unit.title.en} · ${unit.cefrLevel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // FlowRow, not Row: at large system font/display sizes the
                    // two buttons no longer fit side by side and a Row clips the
                    // second one off the card. Wrapping keeps both reachable.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onOpenLesson(unit.id) }) {
                            Text(stringResource(R.string.home_start_lesson))
                        }
                        OutlinedButton(onClick = { onOpenConversation(unit.id) }) {
                            Text(stringResource(R.string.home_start_conversation))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Every engine is lazy, so a cold first conversation stalls once per
        // engine. This gets it over with on demand; progress shows in the
        // status line above and under the TukiStep logcat tag.
        // State lives in the container, not here: the work runs on the app
        // scope and outlives this screen, so a rotate or a trip to Parent Zone
        // must not strand the button reading "Preloading..." forever.
        val preload by container.preload.collectAsState()
        OutlinedButton(
            onClick = { container.preloadAll() },
            enabled = preload != AppContainer.PreloadState.RUNNING,
        ) {
            Text(
                stringResource(
                    when (preload) {
                        AppContainer.PreloadState.RUNNING -> R.string.home_preload_running
                        AppContainer.PreloadState.DONE -> R.string.home_preload_done
                        AppContainer.PreloadState.IDLE -> R.string.home_preload
                    },
                ),
            )
        }
        // Freeform three-way practice with both parrots — no lesson, no
        // scoring, just talking.
        Button(onClick = onOpenChat) {
            Text(stringResource(R.string.home_just_chat))
        }
        // One-tap check of phonemizer -> synthesis -> playback, so a broken
        // voice can be diagnosed without running a whole lesson.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TukiParrot(speaking = container.speaking.collectAsState().value, size = 56.dp)
            OutlinedButton(onClick = { container.testVoice() }) {
                Text(stringResource(R.string.home_test_voice))
            }
        }
        OutlinedButton(onClick = onOpenParent) {
            Text(stringResource(R.string.home_parent_zone))
        }
    }
}

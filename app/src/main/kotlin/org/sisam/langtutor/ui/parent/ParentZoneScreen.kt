package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.ui.common.A11y

/** Simple multiplication gate per Play Families expectations. */
private data class GateChallenge(val question: String, val answer: Int, val options: List<Int>)

private val CHALLENGE = GateChallenge(question = "7 × 6", answer = 42, options = listOf(28, 42, 36, 48))

@Composable
fun ParentZoneScreen(container: AppContainer) {
    var unlocked by remember { mutableStateOf(false) }

    if (!unlocked) {
        ParentGate(onUnlocked = { unlocked = true })
    } else {
        ParentDashboard(container)
    }
}

@Composable
private fun ParentGate(onUnlocked: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrollable: the content is taller than the screen (ten unit
            // cards on Home, the pack list in Parent Zone), and an unscrolled
            // Column silently CLIPS its tail. That hid the Parent Zone button
            // — the only route to installing models — off the bottom edge.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Text(
            text = stringResource(R.string.parent_gate_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = stringResource(R.string.parent_gate_question, CHALLENGE.question))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CHALLENGE.options.forEach { option ->
                OutlinedButton(onClick = { if (option == CHALLENGE.answer) onUnlocked() }) {
                    Text(text = option.toString())
                }
            }
        }
    }
}

@Composable
private fun ParentDashboard(container: AppContainer) {
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
            .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Text(
            text = stringResource(R.string.parent_zone_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = stringResource(R.string.parent_xp, profile.xp))
        Text(text = stringResource(R.string.parent_daily_limit, profile.parentSettings.dailyMinutesLimit))
        Text(text = stringResource(R.string.parent_offline_note))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { setAppLanguage("he") }) {
                Text(stringResource(R.string.parent_language_hebrew))
            }
            OutlinedButton(onClick = { setAppLanguage("en") }) {
                Text(stringResource(R.string.parent_language_english))
            }
        }

        LevelSection(container)
        VoiceSection(container)
        PacksSection(container)
        ReportsSection(container)
        ExperimentalSection(container)
    }
}

private fun setAppLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

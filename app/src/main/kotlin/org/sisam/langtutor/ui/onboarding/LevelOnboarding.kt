package org.sisam.langtutor.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.TukiParrot

/**
 * The one question a fresh install needs answered before anything teaches:
 * what Level is this learner? (docs/learner-levels.md — proficiency, never
 * age.) One screen, seven chips, a skip that lands on the widest default.
 *
 * Shown only while the profile is FRESH — no level chosen, nothing earned,
 * no name — so an existing install (whose legacy track already maps to a
 * level) never sees it, and it can never reappear once any choice is made.
 * The Parent Zone keeps the same chips for changing the answer later.
 */
@Composable
fun LevelOnboarding(container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        TukiParrot(speaking = false, size = A11y.decorativeDp(comfortable = 96, minimum = 56))
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (level in 1..7) {
                FilterChip(
                    selected = false,
                    onClick = {
                        scope.launch {
                            container.profile.update { it.copy(learnerLevel = level) }
                            onDone()
                        }
                    },
                    label = { Text(stringResource(levelLabel(level), level)) },
                )
            }
        }
        TextButton(
            onClick = {
                scope.launch {
                    // The widest default; the Parent Zone can refine it later.
                    container.profile.update { it.copy(learnerLevel = 2) }
                    onDone()
                }
            },
        ) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}

/** A profile that has never been touched — the only audience for the screen. */
fun isFreshProfile(profile: LearnerProfile): Boolean =
    profile.learnerLevel == 0 && profile.xp == 0 &&
        profile.stickers.isEmpty() && profile.childName.isEmpty()

private fun levelLabel(level: Int): Int = when (level) {
    1 -> R.string.level_1
    2 -> R.string.level_2
    3 -> R.string.level_3
    4 -> R.string.level_4
    5 -> R.string.level_5
    6 -> R.string.level_6
    else -> R.string.level_7
}

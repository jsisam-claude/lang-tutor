package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile

/**
 * The learner's Level, 1–7.
 *
 * One choice that moves several dials at once (docs/learner-levels.md): the
 * tutor's register, the reply budget, whether a mistake gets a named rule or
 * a gentle recast, and how much Hebrew scaffolding is offered. Levels are
 * PROFICIENCY, never age — the app serves non-native speakers of all ages,
 * and the old age-flavored tracks are gone (an existing profile lands on the
 * level its track pointed to).
 *
 * Deliberately in the Parent Zone rather than in onboarding for now: it is a
 * setting an adult should be able to change after seeing a session, and the
 * three-question onboarding the plan calls for is a separate piece of work.
 *
 * The pronunciation-gloss switch lives here too, because it is the one dial
 * most likely to be wanted against the level's default — a learner at Level 4
 * who still sounds words out, or a Level 1 adult who finds Hebrew letters
 * under English patronising.
 */
@Composable
fun LevelSection(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_level_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.parent_level_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (level in 1..7) {
                    FilterChip(
                        selected = level == profile.effectiveLevel,
                        onClick = {
                            scope.launch {
                                container.profile.update { it.copy(learnerLevel = level) }
                            }
                        },
                        label = { Text(stringResource(labelFor(level), level)) },
                    )
                }
            }
            // Talks about the model's Hebrew; the practice flavor has no model.
            if (BuildConfig.HAS_LLM) {
                Text(
                    text = stringResource(R.string.parent_level_hebrew_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            // The gloss follows the level unless someone says otherwise, so
            // the switch shows the level's answer until it is touched. That
            // keeps "I never opened this screen" and "I chose this" as
            // different states without a third control to explain.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.parent_gloss_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.parent_gloss_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.parent_gloss_example),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = container.glossEnabled(profile),
                    onCheckedChange = { on ->
                        scope.launch {
                            container.profile.update {
                                it.copy(
                                    parentSettings = it.parentSettings.copy(
                                        showTransliteration = on,
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            // Separate switch, because these are separate questions. A child
            // who cannot read Hebrew still benefits from sounding a word out;
            // an adult who reads both may want the meaning and not the
            // phonetic crutch.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.parent_translation_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.parent_translation_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = container.translationEnabled(profile),
                    onCheckedChange = { on ->
                        scope.launch {
                            container.profile.update {
                                it.copy(
                                    parentSettings = it.parentSettings.copy(
                                        showTranslation = on,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun labelFor(level: Int): Int = when (level) {
    1 -> R.string.level_1
    2 -> R.string.level_2
    3 -> R.string.level_3
    4 -> R.string.level_4
    5 -> R.string.level_5
    6 -> R.string.level_6
    else -> R.string.level_7
}

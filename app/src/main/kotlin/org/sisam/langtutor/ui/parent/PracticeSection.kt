package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.Skill
import org.sisam.langtutor.profile.SkillState

/**
 * What the rooms have learned about the learner (docs/knowledge-tracing.md).
 *
 * Deliberately the WEAKEST few rather than a score. A parent who is shown
 * "72%" learns nothing they can act on; a parent who is shown that *going-to*
 * and *the zoo* are where the answers go wrong has something to talk about
 * over breakfast, which is the only thing this screen can usefully produce.
 *
 * Nothing here is a grade and nothing leaves the device. Where there is not
 * yet enough evidence — fewer than a few answers on anything — the section
 * says so rather than inventing a number, because the honest answer early on
 * is that the app does not know yet.
 */
@Composable
fun PracticeSection(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val weakest = KINDS.mapNotNull { (kind, labelRes) ->
        container.skills.weakest(profile, kind, limit = 3).takeIf { it.isNotEmpty() }?.let { labelRes to it }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_practice_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.parent_practice_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (weakest.isEmpty()) {
                Text(
                    text = stringResource(R.string.parent_practice_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                weakest.forEach { (labelRes, entries) ->
                    HorizontalDivider()
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    entries.forEach { (id, state) -> SkillRow(id, state) }
                }
            }
        }
    }
}

/**
 * One skill: its name, and how far along it is. The bar is a second channel
 * for the number beside it, never the only one — and the whole row reads as
 * one sentence to a screen reader rather than as three fragments.
 */
@Composable
private fun SkillRow(id: String, state: SkillState) {
    val percent = (state.pKnown * 100).toInt()
    val label = Skill.labelOf(id).replace('-', ' ')
    val spoken = stringResource(R.string.parent_practice_row, label, percent, state.attempts)
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        LinearProgressIndicator(
            progress = { state.pKnown.toFloat() },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.parent_practice_tries, percent, state.attempts),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The kinds worth showing a parent, in the order that reads best: what the
 *  learner is saying, then the grammar, then the topics. A `word:` skill is
 *  left out — a list of single words is a spelling test, not a report. */
private val KINDS = listOf(
    "sound" to R.string.parent_practice_sounds,
    "frame" to R.string.parent_practice_frames,
    "theme" to R.string.parent_practice_themes,
)

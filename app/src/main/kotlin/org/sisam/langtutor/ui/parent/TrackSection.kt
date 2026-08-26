package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack

/**
 * Who is learning.
 *
 * One choice that moves several dials at once (docs/learner-tracks.md): the
 * tutor's register, the reply budget, whether a mistake gets a named rule or a
 * gentle recast, and whether Hebrew explanations are offered at all. The
 * levers all existed already — they were hardcoded for a six-year-old.
 *
 * Deliberately in the Parent Zone rather than in onboarding for now: it is a
 * setting an adult should be able to change after seeing a session, and the
 * three-question onboarding the plan calls for is a separate piece of work.
 */
@Composable
fun TrackSection(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.parent_track_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.parent_track_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (track in LearnerTrack.entries) {
                    FilterChip(
                        selected = track == profile.track,
                        onClick = {
                            scope.launch { container.profile.update { it.copy(track = track) } }
                        },
                        label = { Text(stringResource(labelFor(track))) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.parent_track_hebrew_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun labelFor(track: LearnerTrack): Int = when (track) {
    LearnerTrack.PRE_READER -> R.string.track_pre_reader
    LearnerTrack.BEGINNER -> R.string.track_beginner
    LearnerTrack.EXAM -> R.string.track_exam
    LearnerTrack.IMPROVER -> R.string.track_improver
}

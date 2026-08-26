package org.sisam.langtutor.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import org.sisam.langtutor.speech.TukiVoice
import org.sisam.langtutor.speech.TukiVoices
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.TukiParrot
import androidx.compose.ui.Alignment

/**
 * Voice picker.
 *
 * Tapping a voice BOTH selects it and speaks the test line in it — for a
 * choice that is entirely about how something sounds, a separate "preview"
 * control would be ceremony. Switching costs nothing: a Kokoro voice is a
 * 510x256 conditioning table, so there is no model reload between taps.
 *
 * English only by construction ([TukiVoices.ALL]), grouped by accent so the
 * American/British split is visible — that is the choice most likely to matter
 * to a parent picking what their child will imitate.
 */
@Composable
fun VoiceSection(container: AppContainer) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val scope = rememberCoroutineScope()
    val selectedId = TukiVoices.byId(profile.parentSettings.voiceId).id
    val speaking by container.speaking.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tuki moves while the sample plays, so the parent hears AND sees
            // which voice they just picked.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TukiParrot(speaking = speaking, size = A11y.decorativeDp(comfortable = 56, minimum = 36))
                Text(
                    text = stringResource(R.string.parent_voice_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.parent_voice_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            for (accent in TukiVoice.Accent.entries) {
                val voices = container.availableVoices.filter { it.accent == accent }
                if (voices.isEmpty()) continue
                Text(
                    text = stringResource(
                        when (accent) {
                            TukiVoice.Accent.AMERICAN -> R.string.parent_voice_american
                            TukiVoice.Accent.BRITISH -> R.string.parent_voice_british
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (voice in voices) {
                        FilterChip(
                            selected = voice.id == selectedId,
                            onClick = {
                                scope.launch {
                                    container.profile.update {
                                        it.copy(
                                            parentSettings = it.parentSettings.copy(voiceId = voice.id),
                                        )
                                    }
                                }
                                container.previewVoice(voice.id)
                            },
                            label = {
                                Text(
                                    voice.label + if (voice.gender == TukiVoice.Gender.MALE) " ♂" else " ♀",
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
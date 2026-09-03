package org.sisam.langtutor.ui.twisters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.TwisterBook
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EnglishContent
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.drill.DrillPane
import org.sisam.langtutor.ui.drill.DrillSource

/**
 * "Say it fast" — the tongue-twister room (docs/tongue-twisters.md).
 *
 * Sorted by SOUND rather than by level, because that is what these lines are
 * for: each card is one English phoneme Hebrew does not have, or one contrast
 * Hebrew speakers collapse, and the round behind it climbs from three words to
 * a whole clause on that one sound. The drill loop itself is the vocabulary
 * room's — see [DrillPane]; only the pool and the heading differ.
 */
@Composable
fun TwisterScreen(container: AppContainer) {
    var soundKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Same as every room that listens: ask for the mic once, on the way in.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    val book by produceState(initialValue = TwisterBook.EMPTY, container) {
        value = runCatching { container.twisters.book() }.getOrDefault(TwisterBook.EMPTY)
    }
    // Same locale test the Parent Zone uses for its bilingual labels.
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")
    val chosen = soundKey?.let { key -> book.sounds.firstOrNull { it.key == key } }

    if (chosen == null) {
        SoundPicker(book, hebrew, onPick = { soundKey = it })
    } else {
        val label = if (hebrew) chosen.label.he else chosen.label.en
        DrillPane(
            container = container,
            source = DrillSource.Sound(chosen.key),
            heading = stringResource(R.string.twister_round, label),
            pickAnotherLabel = stringResource(R.string.twister_pick_other),
            onPickAnother = { soundKey = null },
        )
    }
}

/** One accent per card, cycled — the pre-reader's handle on a list of sounds
 *  none of which has a picture. */
private val ACCENTS = listOf(
    Color(0xFF19B8A6), Color(0xFF3E8ED0), Color(0xFF7C6BEA),
    Color(0xFFE0679A), Color(0xFFE29B3F),
)

@Composable
private fun SoundPicker(book: TwisterBook, hebrew: Boolean, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TukiParrot(speaking = false, size = A11y.decorativeDp(comfortable = 64, minimum = 40))
            Text(
                text = stringResource(R.string.twister_pick_sound),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.twister_why),
            style = MaterialTheme.typography.bodyMedium,
        )
        book.playableSounds().forEachIndexed { index, sound ->
            val accent = ACCENTS[index % ACCENTS.size]
            val count = book.forSound(sound.key).size
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(sound.key) },
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(A11y.decorativeDp(comfortable = 52, minimum = 40))
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The IPA is the honest label for a sound, and it is
                        // Latin script either way — so it stays LTR even when
                        // everything around it is Hebrew.
                        EnglishContent {
                            Text(
                                text = sound.ipa,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hebrew) sound.label.he else sound.label.en,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.twister_example, sound.example),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.twister_count, count),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

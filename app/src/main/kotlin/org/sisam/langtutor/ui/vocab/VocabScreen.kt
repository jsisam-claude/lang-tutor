package org.sisam.langtutor.ui.vocab

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalConfiguration
import org.sisam.langtutor.content.PhraseTheme
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.tutor.drill.DrillDeck
import org.sisam.langtutor.tutor.drill.DrillLevel
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.drill.DrillPane
import org.sisam.langtutor.ui.drill.DrillSource

/**
 * The vocabulary room: pick a level, then "Repeat after me" — Tuki says a
 * line, the learner says it back, a correct repetition celebrates and moves
 * on. The LLM writes fresh lines each round when a model is installed; the
 * drill LOOP itself never depends on it, so the room still starts instantly
 * from the curriculum deck while the model loads, and works with no model at
 * all.
 */
@Composable
fun VocabScreen(container: AppContainer) {
    var levelName by rememberSaveable { mutableStateOf<String?>(null) }
    // Null is "any topic" — the mixed round the room has always given.
    var theme by rememberSaveable { mutableStateOf<String?>(null) }
    val level = levelName?.let { DrillLevel.valueOf(it) }

    // The drill needs the mic; ask once when the room opens.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    if (level == null) {
        LevelPicker(
            container = container,
            theme = theme,
            onPickTheme = { theme = it },
            onPick = { levelName = it.name },
        )
    } else {
        DrillPane(
            container = container,
            source = DrillSource.Mixed(level, theme),
            heading = stringResource(levelLabel(level)),
            pickAnotherLabel = stringResource(R.string.vocab_pick_other),
            onPickAnother = { levelName = null },
        )
    }
}

/** Colour + glyph per level — the pre-reader's handle on the choice. */
private fun levelLook(level: DrillLevel): Pair<Color, String> = when (level) {
    DrillLevel.WORDS -> Color(0xFF19B8A6) to "🧩"
    DrillLevel.SHORT -> Color(0xFF3E8ED0) to "💬"
    DrillLevel.LONG -> Color(0xFF7C6BEA) to "📖"
}

private fun levelLabel(level: DrillLevel): Int = when (level) {
    DrillLevel.WORDS -> R.string.vocab_level_words
    DrillLevel.SHORT -> R.string.vocab_level_short
    DrillLevel.LONG -> R.string.vocab_level_long
}

@Composable
private fun LevelPicker(
    container: AppContainer,
    theme: String?,
    onPickTheme: (String?) -> Unit,
    onPick: (DrillLevel) -> Unit,
) {
    // Real counts on the cards, from the same pools the round will draw from.
    // With a topic chosen the count is that topic's, at this learner's Level:
    // a card promising eight lines and then giving three is worse than a card
    // that says three.
    val counts by produceState<Map<DrillLevel, Int>>(initialValue = emptyMap(), container, theme) {
        val learnerLevel = container.profile.snapshot().effectiveLevel
        value = if (theme == null) {
            val units = container.content.listUnits().mapNotNull { container.content.loadUnit(it.id) }
            DrillLevel.entries.associateWith { DrillDeck.pool(units, it).size }
        } else {
            val lines = runCatching { container.phrasebank.sentences() }.getOrDefault(emptyList())
            DrillLevel.entries.associateWith {
                DrillDeck.phrasePool(lines, it, learnerLevel, theme).size
            }
        }
    }
    val themes by produceState(initialValue = emptyList<PhraseTheme>(), container) {
        value = runCatching { container.phrasebank.themes() }.getOrDefault(emptyList())
    }
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")

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
                text = stringResource(R.string.vocab_pick_level),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        if (themes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.vocab_pick_topic),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = theme == null,
                    onClick = { onPickTheme(null) },
                    label = { Text(stringResource(R.string.vocab_topic_any)) },
                )
                for (t in themes) {
                    FilterChip(
                        selected = theme == t.id,
                        onClick = { onPickTheme(t.id) },
                        label = {
                            Text(
                                (if (hebrew) t.title?.he else t.title?.en) ?: t.id,
                            )
                        },
                    )
                }
            }
        }
        // A chosen topic is drilled from the bank alone, so the promise of
        // freshly written lines belongs only to the mixed round.
        if (container.usingRealLlm && theme == null) {
            Text(
                text = stringResource(R.string.vocab_fresh),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (level in DrillLevel.entries) {
            val (accent, glyph) = levelLook(level)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(level) },
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
                        Text(text = glyph, style = MaterialTheme.typography.titleLarge)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(levelLabel(level)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        counts[level]?.let { count ->
                            Text(
                                text = stringResource(R.string.vocab_items_count, count),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}


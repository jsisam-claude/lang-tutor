package org.sisam.langtutor.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.BuildConfig
import org.sisam.langtutor.R
import org.sisam.langtutor.content.UnitSummary
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.SkyBackdrop
import org.sisam.langtutor.ui.common.TukiParrot

/**
 * Accent colours for the unit list. A ten-item list of identical white cards
 * is a wall of text to a child who cannot read the titles; a stable colour and
 * glyph per unit gives them something to navigate by and to remember ("the
 * orange one"). Cycled by index, so new units keep working with no data change.
 */
private val UNIT_ACCENTS = listOf(
    Color(0xFFFF6B57), // coral
    Color(0xFF19B8A6), // teal
    Color(0xFFFFC145), // sun
    Color(0xFF7C6BEA), // violet
    Color(0xFF3E8ED0), // sky
    Color(0xFFE0679B), // rose
)

private val UNIT_GLYPHS = listOf("🍎", "🐣", "🎨", "🚌", "🌦", "🏠", "🎵", "🐙", "⚽", "🌙")

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenLesson: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenParent: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenVocab: () -> Unit,
    onOpenPictures: () -> Unit,
    onOpenTwisters: () -> Unit,
) {
    val units by produceState<List<UnitSummary>>(initialValue = emptyList(), container) {
        value = container.content.listUnits()
    }
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)

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
        HomeHero(container = container, xp = profile.xp)

        // The container warms the voice and the mic detector in the background
        // right after launch; this is where that work becomes visible.
        EngineStatusLine()

        // The lesson cards and free chat need the model; the practice flavor
        // (docs/practice-flavor.md) has none, so there its home IS the rooms.
        if (BuildConfig.HAS_LLM) {
            Text(
                text = stringResource(R.string.home_units_heading),
                style = MaterialTheme.typography.titleMedium,
            )

            units.forEachIndexed { index, unit ->
                UnitCard(
                    unit = unit,
                    accent = UNIT_ACCENTS[index % UNIT_ACCENTS.size],
                    glyph = UNIT_GLYPHS[index % UNIT_GLYPHS.size],
                    onOpenLesson = { onOpenLesson(unit.id) },
                    onOpenConversation = { onOpenConversation(unit.id) },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = stringResource(
                if (BuildConfig.HAS_LLM) R.string.home_more_heading else R.string.home_practice_heading,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        // "Repeat after me" drills. Fresh LLM-written lines when a model is
        // installed; the drill loop itself never waits on one, so the room
        // works instantly even while the big brain is still loading.
        Button(onClick = onOpenVocab, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_vocab_room))
        }
        // Recognition before production: see a picture, hear the word, then
        // find the one Tuki asks for. No model, no mic — works on anything.
        Button(onClick = onOpenPictures, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_picture_room))
        }
        // The sounds English has and Hebrew does not. Authored twisters, one
        // round per target sound, scored by the same pronunciation coach —
        // no model needed, so the tablet build has it in full.
        Button(onClick = onOpenTwisters, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_twister_room))
        }
        // Freeform three-way practice with both parrots — no lesson, no
        // scoring, just talking.
        if (BuildConfig.HAS_LLM) {
            Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_just_chat))
            }
        }
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
            modifier = Modifier.fillMaxWidth(),
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
        // One-tap check of phonemizer -> synthesis -> playback, so a broken
        // voice can be diagnosed without running a whole lesson.
        OutlinedButton(
            onClick = { container.testVoice() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_test_voice))
        }
        OutlinedButton(onClick = onOpenParent, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_parent_zone))
        }
    }
}

/**
 * The masthead: Tuki under his own patch of sky, the greeting, and the star
 * count. This is the app's face, and it is also the one place a child can tell
 * at a glance that the voice is working — Tuki's beak moves here whenever
 * anything is speaking, including the voice test below.
 */
@Composable
private fun HomeHero(container: AppContainer, xp: Int) {
    val speaking by container.speaking.collectAsState()
    val parrot = A11y.decorativeDp(comfortable = 96, minimum = 52)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        // Transparent so the sky shows through — but the content colour must
        // be named explicitly, because contentColorFor(Transparent) is
        // Unspecified and would leave every Text below it without one.
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // The sky gives this card its edge; a drop shadow under a transparent
        // container just smudges the boundary.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box {
            // A still sky here, not a drifting one: this backdrop sits directly
            // behind readable text and next to tappable cards, and permanent
            // motion under both is a legibility cost with no payoff.
            SkyBackdrop(
                modifier = Modifier.matchParentSize(),
                bottom = Color(0xFFFFF6EC),
                animated = false,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TukiParrot(speaking = speaking, size = parrot)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_greeting),
                        style = if (A11y.hugeText) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                    )
                    StarChip(xp = xp)
                }
            }
        }
    }
}

/** The XP badge — a pill, so the number reads as a prize rather than a stat. */
@Composable
private fun StarChip(xp: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.home_xp, xp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One unit. The accent stripe and the glyph badge are the navigation aid for a
 * pre-reader; the title and level are for whoever is reading over their
 * shoulder. Both buttons wrap rather than clip when the system font grows.
 */
@Composable
private fun UnitCard(
    unit: UnitSummary,
    accent: Color,
    glyph: String,
    onOpenLesson: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        // IntrinsicSize.Min so the accent stripe can fill the row's height:
        // without it fillMaxHeight() inside a Row resolves to zero.
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Full-height colour stripe — the cheapest possible "which one is
            // this" cue, and it survives any font scale untouched.
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val badge = A11y.decorativeDp(comfortable = 44, minimum = 34)
                    Box(
                        modifier = Modifier
                            .size(badge)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = glyph, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = unit.title.he, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${unit.title.en} · ${unit.cefrLevel}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                // FlowRow, not Row: at large system font/display sizes the
                // two buttons no longer fit side by side and a Row clips the
                // second one off the card. Wrapping keeps both reachable.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onOpenLesson) {
                        Text(
                            stringResource(R.string.home_start_lesson),
                            textAlign = TextAlign.Center,
                        )
                    }
                    OutlinedButton(onClick = onOpenConversation) {
                        Text(
                            stringResource(R.string.home_start_conversation),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

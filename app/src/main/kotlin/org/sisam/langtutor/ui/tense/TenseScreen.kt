package org.sisam.langtutor.ui.tense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.PhraseTheme
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.tutor.tense.TenseEvent
import org.sisam.langtutor.tutor.tense.TenseItem
import org.sisam.langtutor.tutor.tense.TenseOutcome
import org.sisam.langtutor.tutor.tense.TenseSource
import org.sisam.langtutor.tutor.tense.TenseStage
import org.sisam.langtutor.tutor.tense.TenseState
import org.sisam.langtutor.tutor.tense.TenseStop
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.EnglishContent
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.reward.RewardKind

class TenseViewModel(
    private val container: AppContainer,
    private val source: TenseSource,
    private val stage: TenseStage,
) : ViewModel() {

    private val room = container.createTense(viewModelScope)
    val state = room.state

    init {
        viewModelScope.launch { startRound() }
        viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is TenseEvent.Resolved -> when (event.outcome) {
                        TenseOutcome.FIRST_TRY -> container.celebrate(RewardKind.STAR)
                        TenseOutcome.FOUND -> container.celebrate(RewardKind.FLAKE)
                        // Found by elimination: the line is read, nothing bursts.
                        TenseOutcome.SHOWN -> Unit
                    }
                    TenseEvent.Wrong -> Unit
                    is TenseEvent.RoundDone -> if (event.total > 0) container.celebrate(RewardKind.MIX)
                }
            }
        }
    }

    private suspend fun startRound() {
        val level = container.profile.snapshot().effectiveLevel
        val deck = runCatching { container.tenseDeck() }.getOrNull()
        val items = deck?.let {
            // Weakest first: the tenses this learner has misplaced most lead
            // the round (docs/knowledge-tracing.md).
            runCatching { it.round(source, stage, level, Random.Default, mastery = container.masteryLens()) }
                .getOrDefault(emptyList())
        }.orEmpty()
        room.startRound(items, deck?.stops(source, stage, level).orEmpty())
    }

    fun again() {
        viewModelScope.launch { startRound() }
    }

    fun onStopPicked(stop: TenseStop) = room.onStopPicked(stop)
    fun onNext() = room.onNext()
    fun onSentenceTapped() = room.onSentenceTapped()

    /** Silence this room now, WITHOUT ending its round — a chip change keys a
     *  new room while this one stays retained, and a round ended here could
     *  never be resumed. The fill-the-gap room's rule, for the same reason. */
    fun stop() = room.silence()

    /** The other half of [stop]: a room that survived a rotation gets its
     *  voice back, or it reads nothing aloud for the rest of the round. */
    fun start() = room.resume()

    override fun onCleared() = room.shutdown()
}

/**
 * The tense room (docs/tense-room.md): one sentence, and a timeline to put it
 * on.
 *
 * The timeline is laid out with a plain [Row], so it follows the ambient
 * layout direction and the past sits on the RIGHT in Hebrew — time runs the
 * way the eye runs. The English inside it stays left-to-right through
 * [EnglishContent], which is the same split every other room makes.
 */
@Composable
fun TenseScreen(container: AppContainer) {
    var sourceKey by rememberSaveable { mutableStateOf(TenseSource.All.sessionKey) }
    var stageName by rememberSaveable { mutableStateOf(TenseStage.TIME.name) }
    val source = remember(sourceKey) { TenseSource.parse(sourceKey) }
    val stage = remember(stageName) { runCatching { TenseStage.valueOf(stageName) }.getOrDefault(TenseStage.TIME) }
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val level = profile.effectiveLevel
    val themes by produceState(initialValue = emptyList<PhraseTheme>(), container) {
        value = runCatching { container.phrasebank.themes() }.getOrDefault(emptyList())
    }
    // A chip with fewer than two destinations behind it is not offered: with
    // one, there is no question, and the room would ask a learner to tap the
    // only button on screen.
    val offered by produceState(initialValue = emptyMap<String, Boolean>(), container, level, stage, themes) {
        value = runCatching {
            val deck = container.tenseDeck()
            buildMap {
                for (t in themes) put(TenseSource.Theme(t.id).sessionKey, deck.offers(TenseSource.Theme(t.id), stage, level))
            }
        }.getOrDefault(emptyMap())
    }
    val stages by produceState(initialValue = listOf(TenseStage.TIME), container, level) {
        value = runCatching {
            val deck = container.tenseDeck()
            TenseStage.entries.filter { deck.offers(TenseSource.All, it, level) }
        }.getOrDefault(listOf(TenseStage.TIME))
    }
    val viewModel: TenseViewModel = viewModel(
        // The stage is part of the key: it changes the whole question, so it
        // starts a new room exactly as a topic chip does.
        key = "$sourceKey|$stageName",
        factory = viewModelFactory { initializer { TenseViewModel(container, source, stage) } },
    )
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = A11y.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TukiParrot(
                speaking = state.speaking,
                size = A11y.decorativeDp(comfortable = 56, minimum = 36),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tense_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                val progress = when (val s = state) {
                    is TenseState.Asking -> s.asked + 1 to s.total
                    is TenseState.Revealed -> s.asked + 1 to s.total
                    else -> null
                }
                if (progress != null) {
                    Text(
                        text = stringResource(R.string.vocab_progress, progress.first, progress.second),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The stage chips come first and only when there is a choice: at
            // most Levels there is only one stage the bank can fill.
            if (stages.size > 1) {
                for (s in stages) {
                    FilterChip(
                        selected = stage == s,
                        onClick = { stageName = s.name },
                        label = {
                            Text(
                                stringResource(
                                    if (s == TenseStage.TIME) R.string.tense_stage_time else R.string.tense_stage_aspect,
                                ),
                            )
                        },
                    )
                }
            }
            FilterChip(
                selected = source == TenseSource.All,
                onClick = { sourceKey = TenseSource.All.sessionKey },
                label = { Text(stringResource(R.string.vocab_topic_any)) },
            )
            for (theme in themes) {
                val key = TenseSource.Theme(theme.id).sessionKey
                if (offered[key] != true) continue
                FilterChip(
                    selected = sourceKey == key,
                    onClick = { sourceKey = key },
                    label = { Text((if (hebrew) theme.title?.he else theme.title?.en) ?: theme.id) },
                )
            }
        }
        EngineStatusLine()

        when (val s = state) {
            TenseState.Idle -> Box(modifier = Modifier.weight(1f))

            is TenseState.Asking -> ItemPane(
                item = s.item,
                stops = s.stops,
                wrongTaps = s.wrongTaps,
                placed = s.placed,
                revealed = false,
                outcome = null,
                onPick = viewModel::onStopPicked,
                onSentenceTapped = viewModel::onSentenceTapped,
                onNext = {},
                modifier = Modifier.weight(1f),
            )

            is TenseState.Revealed -> ItemPane(
                item = s.item,
                stops = s.stops,
                wrongTaps = s.wrongTaps,
                placed = s.placed,
                revealed = true,
                outcome = s.outcome,
                onPick = {},
                onSentenceTapped = viewModel::onSentenceTapped,
                onNext = viewModel::onNext,
                modifier = Modifier.weight(1f),
            )

            is TenseState.Done -> DonePane(
                state = s,
                onAgain = viewModel::again,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TenseState.speaking: Boolean
    get() = when (this) {
        is TenseState.Asking -> speaking
        is TenseState.Revealed -> speaking
        else -> false
    }

@Composable
private fun ItemPane(
    item: TenseItem,
    stops: List<TenseStop>,
    wrongTaps: Set<TenseStop>,
    placed: Map<TenseStop, List<String>>,
    revealed: Boolean,
    outcome: TenseOutcome?,
    onPick: (TenseStop) -> Unit,
    onSentenceTapped: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sentence: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            TenseSentence(item = item, revealed = revealed, onTap = onSentenceTapped)
            // The word the deck took out, handed back once the tap is spent.
            // A line that never carried one says nothing here: its verb mark
            // is the whole explanation.
            val hidden = item.hidden
            if (hidden != null && (revealed || wrongTaps.isNotEmpty())) {
                Text(
                    text = stringResource(R.string.tense_hidden, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
            // The nudge stays while the item has no hidden word to hand back:
            // otherwise a wrong tap on a clean line clears the hint and puts
            // nothing in its place, and the learner is told only that they
            // were wrong.
            if (!revealed && (wrongTaps.isEmpty() || item.hidden == null) && !A11y.cramped) {
                Text(
                    text = stringResource(R.string.tense_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (outcome == TenseOutcome.SHOWN) {
                Text(
                    text = stringResource(R.string.cloze_shown, stringResource(stops.labelOf(item.stop))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Timeline(
            stops = stops,
            answer = item.stop,
            wrongTaps = wrongTaps,
            placed = placed,
            revealed = revealed,
            onPick = onPick,
        )
        if (revealed) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.picture_next))
            }
        }
    }
    if (A11y.wideViewport) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(A11y.sectionGap),
        ) {
            sentence(Modifier.weight(1.2f).fillMaxHeight())
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                controls()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
        ) {
            sentence(Modifier.weight(1f).fillMaxWidth())
            controls()
        }
    }
}

/**
 * The sentence as the learner sees it — the deck's [TenseItem.shown], with
 * the time word already out.
 *
 * On the reveal the form that carries the tense is marked, and the line does
 * NOT become [TenseItem.restored]: the verb indices point into `shown`, and a
 * time word lifted off the FRONT shifted every one of them, so re-rendering
 * the restored line would underline the wrong word. Tuki speaks the restored
 * line instead — the ear gets the whole sentence, the eye keeps the one the
 * marks belong to.
 */
@Composable
private fun TenseSentence(item: TenseItem, revealed: Boolean, onTap: () -> Unit) {
    val repeatLabel = stringResource(R.string.tense_repeat)
    val words = item.words
    val marked = item.verb.toSet()
    val accent = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(" ")
            if (revealed && i in marked) {
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(w) }
            } else {
                append(w)
            }
        }
    }
    EnglishContent {
        Text(
            text = text,
            style = if (A11y.hugeText) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = repeatLabel, onClick = onTap)
                .padding(8.dp),
        )
    }
}

/**
 * The three destinations, as a timeline.
 *
 * A plain [Row], so the ambient layout direction decides which end the past
 * sits at: left-to-right in English, right-to-left in Hebrew. Nothing here is
 * re-authored per language — one row does the whole job.
 *
 * A wrong destination is struck through, dimmed and disabled, and the right
 * one takes a tick, which is the fill-the-gap room's treatment exactly (three
 * channels, never colour alone) so the two rooms behave the same under the
 * finger and under the screen reader.
 */
@Composable
private fun Timeline(
    stops: List<TenseStop>,
    answer: TenseStop,
    wrongTaps: Set<TenseStop>,
    placed: Map<TenseStop, List<String>>,
    revealed: Boolean,
    onPick: (TenseStop) -> Unit,
) {
    val wrongLabel = stringResource(R.string.cloze_option_wrong)
    val rightLabel = stringResource(R.string.cloze_option_right)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The rail is decoration and yields when the screen is tight: the
        // destinations are the controls and they never do.
        if (!A11y.cramped) {
            Row(
                modifier = Modifier.fillMaxWidth().height(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (stop in stops) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    color = if (placed[stop].orEmpty().isNotEmpty()) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (stop in stops) {
                val wrong = stop in wrongTaps
                val right = revealed && stop == answer
                OutlinedButton(
                    onClick = { onPick(stop) },
                    enabled = !wrong && !revealed,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = A11y.tapTargetDp(comfortable = 72, minimum = 64))
                        .semantics {
                            stateDescription = when {
                                right -> rightLabel
                                wrong -> wrongLabel
                                else -> ""
                            }
                        },
                ) {
                    val label = stringResource(stop.label)
                    Text(
                        text = if (right) "✓ $label" else label,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (wrong) TextDecoration.LineThrough else null,
                        color = when {
                            wrong -> MaterialTheme.colorScheme.error
                            right -> MaterialTheme.colorScheme.secondary
                            else -> Color.Unspecified
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        // What has been filed so far, under the heading it went to. This is
        // the payoff kept from the card-sorting idea the room did not take.
        if (placed.values.any { it.isNotEmpty() } && !A11y.cramped) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (stop in stops) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (line in placed[stop].orEmpty()) {
                            EnglishContent {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonePane(state: TenseState.Done, onAgain: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap, Alignment.CenterVertically),
    ) {
        if (state.total == 0) {
            // Nothing to place at this Level: the chips are the way out.
            Text(
                text = stringResource(R.string.tense_empty),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.picture_done, state.firstTry, state.total),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            // The sorted timeline, read across: the thing the round was for.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (stop in state.stops) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(stop.label),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        for (line in state.placed[stop].orEmpty()) {
                            EnglishContent {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            Button(onClick = onAgain) { Text(stringResource(R.string.vocab_again)) }
        }
    }
}

/** The string a destination wears. One place, so the room and its round-up
 *  cannot disagree about what a stop is called. */
private val TenseStop.label: Int
    get() = when (this) {
        TenseStop.YESTERDAY -> R.string.tense_stop_yesterday
        TenseStop.TODAY -> R.string.tense_stop_today
        TenseStop.TOMORROW -> R.string.tense_stop_tomorrow
        TenseStop.BEFORE_THAT -> R.string.tense_stop_before
        TenseStop.FINISHED -> R.string.tense_stop_finished
        TenseStop.STILL_GOING -> R.string.tense_stop_still_going
    }

private fun List<TenseStop>.labelOf(stop: TenseStop): Int = (firstOrNull { it == stop } ?: stop).label

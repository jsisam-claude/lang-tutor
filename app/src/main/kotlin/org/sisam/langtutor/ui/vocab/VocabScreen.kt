package org.sisam.langtutor.ui.vocab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.engine.TurnLatency
import org.sisam.langtutor.tutor.drill.DrillDeck
import org.sisam.langtutor.tutor.drill.DrillEvent
import org.sisam.langtutor.tutor.drill.DrillItem
import org.sisam.langtutor.tutor.drill.DrillLevel
import org.sisam.langtutor.tutor.drill.DrillOrchestrator
import org.sisam.langtutor.tutor.drill.DrillState
import org.sisam.langtutor.tutor.drill.WordMatch
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.EnglishContent
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.rememberTranslation
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.common.PronunciationFeedback
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.reward.RewardKind

class DrillViewModel(
    private val container: AppContainer,
    private val level: DrillLevel,
) : ViewModel() {

    private val drill: DrillOrchestrator = container.createDrillOrchestrator(viewModelScope)

    /** The sentence writer; null in demo mode, and optional always. */
    private val generator = container.createDrillGenerator()

    val state = drill.state
    val pronunciation = drill.pronunciation

    private var units: List<CurriculumUnit> = emptyList()

    /** The next round, written while the current one is being played, so
     *  "Again!" is instant instead of waiting out a fresh generation. */
    private var next: List<DrillItem>? = null
    private var prefetch: Job? = null

    init {
        // Start the model load NOW, in parallel with loading the units — by
        // the time the child has heard the intro the writer is usually ready.
        viewModelScope.launch { runCatching { generator?.prepare() } }
        viewModelScope.launch {
            units = container.content.listUnits().mapNotNull { container.content.loadUnit(it.id) }
            startRound()
        }
        // Celebrations live HERE, not in the composable: they are events in the
        // session, and the session outlives the composition — a rotation must
        // not replay the last one (the lesson the conversation screen learned).
        viewModelScope.launch {
            drill.events.collect { event ->
                when (event) {
                    is DrillEvent.Correct -> container.celebrate(RewardKind.STAR)
                    DrillEvent.Nearly -> container.celebrate(RewardKind.FLAKE)
                    DrillEvent.TooQuiet -> Unit
                }
            }
        }
        viewModelScope.launch {
            // StateFlow dedupes, and RoundDone carries a round counter — so
            // this fires exactly once per finished round, even at equal scores.
            drill.state.collect { if (it is DrillState.RoundDone) container.celebrate(RewardKind.MIX) }
        }
    }

    fun onMicPressed() = drill.onMicPressed()
    fun onMicReleased() = drill.onMicReleased()

    fun again() {
        viewModelScope.launch {
            val round = next ?: freshRound()
            next = null
            drill.startRound(round)
            prefetchNext()
        }
    }

    private suspend fun startRound() {
        drill.startRound(freshRound())
        prefetchNext()
    }

    /**
     * LLM-written lines first, curriculum deck to top up. The writer may
     * return few or none — a cold model, a failed generation, or every line
     * eaten by the gauntlet — and the round must be full regardless.
     */
    private suspend fun freshRound(): List<DrillItem> {
        val size = DrillDeck.sizeFor(level)
        val written = generator?.let { g ->
            runCatching { g.generate(level, size, Random.Default) }.getOrElse { emptyList() }
        } ?: emptyList()
        return (written + DrillDeck.round(units, level, Random.Default))
            .distinctBy { WordMatch.tokens(it.text) }
            .take(size)
    }

    private fun prefetchNext() {
        if (generator == null || prefetch?.isActive == true) return
        prefetch = viewModelScope.launch {
            next = runCatching { freshRound() }.getOrNull()
        }
    }

    override fun onCleared() {
        drill.shutdown()
        // Same thermal doctrine as every room: nothing stays loaded after the
        // screen goes away.
        generator?.shutdown()
    }
}

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
    val level = levelName?.let { DrillLevel.valueOf(it) }

    // The drill needs the mic; ask once when the room opens.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    if (level == null) {
        LevelPicker(container, onPick = { levelName = it.name })
    } else {
        DrillPane(container, level, onPickAnother = { levelName = null })
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
private fun LevelPicker(container: AppContainer, onPick: (DrillLevel) -> Unit) {
    // Real counts on the cards, from the same pools the round will draw from.
    val counts by produceState<Map<DrillLevel, Int>>(initialValue = emptyMap(), container) {
        val units = container.content.listUnits().mapNotNull { container.content.loadUnit(it.id) }
        value = DrillLevel.entries.associateWith { DrillDeck.pool(units, it).size }
    }

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
        if (container.usingRealLlm) {
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

@Composable
private fun DrillPane(container: AppContainer, level: DrillLevel, onPickAnother: () -> Unit) {
    val viewModel: DrillViewModel = viewModel(
        key = level.name,
        factory = viewModelFactory {
            initializer { DrillViewModel(container, level) }
        },
    )
    val state by viewModel.state.collectAsState()
    val pronunciation by viewModel.pronunciation.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = A11y.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TukiParrot(
                speaking = state is DrillState.Prompting,
                size = A11y.decorativeDp(comfortable = 72, minimum = 44),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(levelLabel(level)),
                    style = MaterialTheme.typography.titleMedium,
                )
                val active = state as? DrillState.Active
                Text(
                    text = when {
                        active != null -> stringResource(
                            R.string.vocab_progress, active.index + 1, active.total,
                        )
                        else -> stateLabel(state)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // The voice loads lazily; the first line of a cold session says why
        // it is taking a moment.
        EngineStatusLine()

        when (val s = state) {
            is DrillState.RoundDone -> RoundDoneView(
                s,
                onAgain = { viewModel.again() },
                onPickAnother = onPickAnother,
            )

            is DrillState.Active -> {
                // The line itself, big: the karaoke text for whoever reads,
                // decoration for whoever does not — the AUDIO is the content.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // The target, with its Hebrew pronunciation under each
                    // word when the learner's track wants one. This is the
                    // room the gloss exists for: "repeat after me" asks a
                    // child to say a line, and a line they cannot decode is a
                    // line they can only guess at from the audio.
                    val gloss by rememberGloss(container, s.item.text)
                    val lineStyle = if (A11y.hugeText) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.displaySmall
                    }
                    // The curriculum's own Hebrew where the item has it;
                    // generated lines carry none and simply show two rows.
                    val meaning by rememberTranslation(container, s.item.hebrew)
                    if (gloss.isEmpty() && meaning == null) {
                        EnglishContent {
                            Text(
                                text = s.item.text,
                                style = lineStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        GlossedText(
                            words = gloss.ifEmpty { listOf(GlossWord(s.item.text, "")) },
                            style = lineStyle,
                            glossStyle = if (A11y.hugeText) {
                                MaterialTheme.typography.bodyLarge
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                            translation = meaning,
                        )
                    }
                }
                Text(
                    text = stateLabel(s),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                pronunciation?.let { PronunciationFeedback(it) }
                Mic(
                    state = s,
                    onPressed = viewModel::onMicPressed,
                    onReleased = viewModel::onMicReleased,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    text = stringResource(R.string.conversation_hold_to_talk),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            else -> Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Mic(
    state: DrillState.Active,
    onPressed: () -> Unit,
    onReleased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = state is DrillState.AwaitingChild || state is DrillState.Listening
    // Read through rememberUpdatedState: the gesture block below is keyed on
    // Unit and outlives recompositions, so a captured `state` would be stale.
    val stateNow by rememberUpdatedState(state)
    Box(
        modifier = modifier
            .size(A11y.tapTargetDp(comfortable = 88, minimum = 64))
            .background(
                when {
                    state is DrillState.Listening -> MaterialTheme.colorScheme.error
                    open -> MaterialTheme.colorScheme.primary
                    // Tuki is talking or judging — visibly not the moment.
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                CircleShape,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Only time a press the orchestrator will ACT on. A tap
                        // while Tuki is talking is ignored by the state machine
                        // but used to start the clock anyway, and the audio
                        // already playing then closed it with a meaningless
                        // 300ms — an instrument that reported success for a
                        // turn that had not happened.
                        val accepted = stateNow is DrillState.AwaitingChild
                        onPressed()
                        tryAwaitRelease()
                        // The wait the learner FEELS starts here, not when
                        // some engine does.
                        if (accepted) TurnLatency.mark("drill mic release")
                        onReleased()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "🎙️", style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun RoundDoneView(
    done: DrillState.RoundDone,
    onAgain: () -> Unit,
    onPickAnother: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Text(
            text = stringResource(R.string.vocab_round_done),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.vocab_round_score, done.correct, done.total),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAgain) { Text(stringResource(R.string.vocab_again)) }
        OutlinedButton(onClick = onPickAnother) { Text(stringResource(R.string.vocab_pick_other)) }
    }
}

@Composable
private fun stateLabel(state: DrillState): String = when (state) {
    is DrillState.Prompting -> stringResource(R.string.state_speaking)
    is DrillState.AwaitingChild -> stringResource(R.string.state_your_turn)
    is DrillState.Listening -> stringResource(R.string.state_listening)
    is DrillState.Judging -> stringResource(R.string.state_transcribing)
    else -> stringResource(R.string.state_preparing)
}

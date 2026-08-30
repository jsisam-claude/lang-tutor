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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.engine.Karaoke
import org.sisam.langtutor.engine.ListeningAck
import org.sisam.langtutor.engine.TurnLatency
import org.sisam.langtutor.speech.KaraokeTiming
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
    private var phrases: List<PhraseSentence> = emptyList()

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
            phrases = runCatching { container.phrasebank.sentences() }.getOrDefault(emptyList())
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

    val missedWords = drill.lastMissedWords

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
     * A mixed round: the phrasebank — authored, reviewed, level-tagged, with
     * its own Hebrew meaning — takes at most HALF the slots, so the
     * LLM-written lines (fresh topics every round) and the curriculum deck
     * keep the other half instead of being starved by a bank that can always
     * fill a round alone. Any source may come up short — a cold model, a
     * failed generation, a thin level — so the bank tops the round back up
     * at the end and the round is full regardless.
     */
    private suspend fun freshRound(): List<DrillItem> {
        val size = DrillDeck.sizeFor(level)
        val learnerLevel = container.profile.snapshot().effectiveLevel
        val banked = DrillDeck.phraseRound(phrases, level, learnerLevel, Random.Default)
        val written = generator?.let { g ->
            runCatching { g.generate(level, size, Random.Default) }.getOrElse { emptyList() }
        } ?: emptyList()
        return (banked.take((size + 1) / 2) + written + DrillDeck.round(units, level, Random.Default) + banked)
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

    // The turn can now end BEFORE the finger lifts (early close on a target
    // match), so the felt clock and the "heard you" blip key on the state
    // machine's Listening -> Judging transition — the one moment that is the
    // end of the turn on both paths — instead of on the release gesture.
    var wasListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val listening = state is DrillState.Listening
        if (wasListening && state is DrillState.Judging) {
            TurnLatency.mark("drill turn end")
            ListeningAck.play()
        }
        wasListening = listening
    }

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
                val target: @Composable (Modifier) -> Unit = { paneModifier ->
                Box(
                    modifier = paneModifier,
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

                    // Karaoke, both directions. While Tuki SAYS the line, the
                    // word that is sounding right now is bolded (the engine's
                    // playhead-driven position, keyed to this exact text so a
                    // stale utterance can never light the wrong line). After a
                    // judged attempt, the words the child missed are marked
                    // until the item changes, so the retry has a target.
                    val spans = KaraokeTiming.wordSpans(s.item.text)
                    val karaoke by Karaoke.position.collectAsState()
                    val highlightIndex = karaoke
                        ?.takeIf { it.utterance == s.item.text }
                        ?.let { pos -> spans.indexOfFirst { (st, en) -> pos.charStart in st until en } }
                        ?.takeIf { it >= 0 }
                    val missedRaw by viewModel.missedWords.collectAsState()
                    // Token index == whitespace-word index only when the counts
                    // agree (a hyphenated word splits into two tokens); when
                    // they do not, no marks beat wrong marks.
                    val missed = if (
                        missedRaw.isNotEmpty() && WordMatch.tokens(s.item.text).size == spans.size
                    ) {
                        missedRaw
                    } else {
                        emptySet()
                    }
                    if (gloss.isEmpty() && meaning == null) {
                        EnglishContent {
                            Text(
                                text = buildAnnotatedString {
                                    append(s.item.text)
                                    highlightIndex?.let { i ->
                                        spans.getOrNull(i)?.let { (st, en) ->
                                            addStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                ),
                                                st, en,
                                            )
                                        }
                                    }
                                    missed.forEach { i ->
                                        spans.getOrNull(i)?.let { (st, en) ->
                                            addStyle(
                                                SpanStyle(color = MaterialTheme.colorScheme.error),
                                                st, en,
                                            )
                                        }
                                    }
                                },
                                style = lineStyle,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        // The gloss splits per whitespace word, the same shape
                        // as the spans — guarded so a mismatch drops the marks
                        // rather than shifting them onto neighbours.
                        val aligned = gloss.size == spans.size
                        GlossedText(
                            words = gloss.ifEmpty { listOf(GlossWord(s.item.text, "")) },
                            style = lineStyle,
                            glossStyle = if (A11y.hugeText) {
                                MaterialTheme.typography.bodyLarge
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                            translation = meaning,
                            highlightWordIndex = highlightIndex.takeIf { aligned },
                            missedWords = if (aligned) missed else emptySet(),
                        )
                    }
                }
                }
                val controls: @Composable ColumnScope.() -> Unit = {
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
                if (A11y.wideViewport) {
                    // Sideways, the line and the mic become columns: the
                    // interlinear line is the thing that WANTS the width
                    // (docs/bilingual-gloss.md), and the stacked layout was
                    // built for the axis a rotation takes away.
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(A11y.sectionGap),
                    ) {
                        target(Modifier.weight(1.4f).fillMaxHeight())
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(
                                A11y.sectionGap,
                                Alignment.CenterVertically,
                            ),
                        ) {
                            controls()
                        }
                    }
                } else {
                    target(Modifier.fillMaxWidth().weight(1f))
                    controls()
                }
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
                        onPressed()
                        tryAwaitRelease()
                        // The clock and the ack blip live on the Listening ->
                        // Judging transition (see DrillPane), which covers
                        // both this release and an early close equally — and
                        // ignores presses the state machine ignored.
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

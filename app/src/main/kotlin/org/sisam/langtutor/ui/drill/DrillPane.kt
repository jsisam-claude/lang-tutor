package org.sisam.langtutor.ui.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.CurriculumUnit
import org.sisam.langtutor.content.PhraseSentence
import org.sisam.langtutor.content.Twister
import org.sisam.langtutor.engine.Karaoke
import org.sisam.langtutor.engine.ListeningAck
import org.sisam.langtutor.engine.TurnLatency
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
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
import org.sisam.langtutor.ui.common.micSemantics
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.PronunciationFeedback
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.common.rememberTranslation
import org.sisam.langtutor.ui.reward.RewardKind

/**
 * Where a round's lines come from.
 *
 * [Mixed] is the vocabulary room: the curriculum deck, the phrasebank and,
 * when a model is installed, freshly written lines, bucketed by sentence
 * length. [Sound] is the tongue-twister room: every line that drills one
 * target sound, easiest to say first, so the round IS the progression.
 */
sealed interface DrillSource {

    /** Identifies the session, so leaving a room and entering another one
     *  starts a new drill instead of resuming someone else's round. */
    val sessionKey: String

    /**
     * [theme] narrows the bank to one topic. Null is the whole bank, which is
     * also the only shape that mixes in generated and curriculum lines: once
     * a learner has asked for the farm, a round of the farm is what they
     * should get, not a farm line followed by four about the doctor.
     */
    data class Mixed(val level: DrillLevel, val theme: String? = null) : DrillSource {
        override val sessionKey get() = "mixed:${level.name}:${theme ?: "any"}"
    }

    data class Sound(val key: String) : DrillSource {
        override val sessionKey get() = "sound:$key"
    }
}

class DrillViewModel(
    private val container: AppContainer,
    private val source: DrillSource,
) : ViewModel() {

    private val drill: DrillOrchestrator = container.createDrillOrchestrator(viewModelScope)

    /** The sentence writer; null in demo mode, and optional always. A
     *  tongue-twister round is entirely authored, so it never asks for one. */
    private val generator =
        if (source is DrillSource.Sound) null else container.createDrillGenerator()

    val state = drill.state
    val pronunciation = drill.pronunciation

    private var units: List<CurriculumUnit> = emptyList()
    private var phrases: List<PhraseSentence> = emptyList()
    private var twisters: List<Twister> = emptyList()

    /** The next round, written while the current one is being played, so
     *  "Again!" is instant instead of waiting out a fresh generation. */
    private var next: List<DrillItem>? = null
    private var prefetch: Job? = null

    init {
        // A twister card names the sound it drills, so the voice must not
        // rewrite it. See [AppContainer.setPlainSpeech].
        if (source is DrillSource.Sound) container.setPlainSpeech(true)
        // Start the model load NOW, in parallel with loading the units — by
        // the time the child has heard the intro the writer is usually ready.
        viewModelScope.launch { runCatching { generator?.prepare() } }
        viewModelScope.launch {
            when (source) {
                is DrillSource.Mixed -> {
                    units = container.content.listUnits().mapNotNull { container.content.loadUnit(it.id) }
                    phrases = runCatching { container.phrasebank.sentences() }.getOrDefault(emptyList())
                }
                is DrillSource.Sound -> {
                    twisters = runCatching { container.twisters.book().forSound(source.key) }
                        .getOrDefault(emptyList())
                }
            }
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
    val speculative = drill.speculative

    private var starting: Job? = null

    /**
     * Start (or restart) a round, at most one at a time.
     *
     * Was re-entrant: every tap of "Again!" launched another round build, and
     * two arriving together reset the orchestrator underneath each other. It
     * also left the finished-round card on screen while the new round loaded,
     * so re-picking the level you had just finished dropped you back on the
     * old score instead of a drill.
     */
    fun again() {
        if (starting?.isActive == true) return
        starting = viewModelScope.launch {
            _restarting.value = true
            try {
                val round = next ?: freshRound()
                next = null
                drill.startRound(round)
                prefetchNext()
            } finally {
                _restarting.value = false
            }
        }
    }

    private val _restarting = MutableStateFlow(false)

    /** True while a new round is being built — the round-done card must not
     *  stay up over it, or the learner taps a score they already finished. */
    val restarting: StateFlow<Boolean> = _restarting

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
     *
     * A tongue-twister round is the opposite shape and deliberately so: it is
     * every line for one sound, in authored order, with nothing generated
     * mixed in. The lines exist to be hard to say in a specific way, which is
     * not something a sentence writer can be asked for.
     */
    private suspend fun freshRound(): List<DrillItem> = when (source) {
        is DrillSource.Sound -> DrillDeck.twisterRound(twisters)
        is DrillSource.Mixed -> {
            val level = source.level
            val size = DrillDeck.sizeFor(level)
            val learnerLevel = container.profile.snapshot().effectiveLevel
            val banked =
                DrillDeck.phraseRound(phrases, level, learnerLevel, Random.Default, source.theme)
            if (source.theme != null) {
                // A chosen topic is the whole round. Nothing generated and
                // nothing from the curriculum: those cannot be held to the
                // topic, and a round that wanders off it is not the round the
                // learner asked for. A thin level simply gives a short round.
                banked.distinctBy { WordMatch.tokens(it.text) }
            } else {
                val written = generator?.let { g ->
                    runCatching { g.generate(level, size, Random.Default) }.getOrElse { emptyList() }
                } ?: emptyList()
                (banked.take((size + 1) / 2) + written + DrillDeck.round(units, level, Random.Default) + banked)
                    .distinctBy { WordMatch.tokens(it.text) }
                    .take(size)
            }
        }
    }

    private fun prefetchNext() {
        // A twister round is the same authored list every time, so there is
        // nothing to write ahead — and no reason to hold a model for it.
        if (source is DrillSource.Sound || generator == null || prefetch?.isActive == true) return
        prefetch = viewModelScope.launch {
            next = runCatching { freshRound() }.getOrNull()
        }
    }

    override fun onCleared() {
        if (source is DrillSource.Sound) container.setPlainSpeech(false)
        drill.shutdown()
        // Same thermal doctrine as every room: nothing stays loaded after the
        // screen goes away.
        generator?.shutdown()
    }
}

/**
 * The repeat-after-me pane: the machine both practice rooms are built on.
 *
 * Tuki says a line, the learner says it back, a correct repetition celebrates
 * and moves on. What differs between rooms is only WHERE the lines come from
 * ([DrillSource]) and what the heading calls them — the loop, the karaoke, the
 * gloss, the mic and the round summary are one implementation, because a
 * second copy of them would drift.
 */
@Composable
fun DrillPane(
    container: AppContainer,
    source: DrillSource,
    /** What this room calls the round, shown beside Tuki. */
    heading: String,
    /** What the button back to this room's picker says. */
    pickAnotherLabel: String,
    onPickAnother: () -> Unit,
) {
    val viewModel: DrillViewModel = viewModel(
        key = source.sessionKey,
        factory = viewModelFactory {
            initializer { DrillViewModel(container, source) }
        },
    )
    val state by viewModel.state.collectAsState()
    val pronunciation by viewModel.pronunciation.collectAsState()
    // While a new round is being built the finished-round card must come down,
    // or re-picking the level you just finished lands you on its old score.
    val restarting by viewModel.restarting.collectAsState()

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
                    text = heading,
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

        when (val s = if (restarting) DrillState.Idle else state) {
            is DrillState.RoundDone -> RoundDoneView(
                s,
                pickAnotherLabel = pickAnotherLabel,
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
                            // Meaning-row karaoke: only a phrasebank line has
                            // cues, and only its own Hebrew matches them.
                            translationCues = s.item.align.takeIf { meaning == s.item.hebrew },
                            // A generated line has no authored meaning; the
                            // picture set stands in under matching words.
                            showWordIcons = meaning == null,
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
                    // "Heard so far", live — a guess, quoted and muted.
                    var heard by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(s is DrillState.Listening) {
                        heard = null
                        if (s is DrillState.Listening) {
                            viewModel.speculative.collect { heard = it }
                        }
                    }
                    if (s is DrillState.Listening && !heard.isNullOrBlank()) {
                        EnglishContent {
                            Text(
                                text = "“$heard”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
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
    val listening = state is DrillState.Listening
    Box(
        modifier = modifier
            .size(A11y.tapTargetDp(comfortable = 88, minimum = 64))
            // Without this the mic is a Box with a gesture detector: correct
            // for press-and-hold, invisible to every assistive service, and
            // the room has no other way in. Adds no touch behaviour.
            .micSemantics(
                listening = listening,
                enabled = open,
                label = stringResource(R.string.mic_label),
                actionLabel = stringResource(
                    if (listening) R.string.mic_stop else R.string.mic_start,
                ),
                state = stringResource(
                    when {
                        listening -> R.string.mic_state_listening
                        open -> R.string.mic_state_idle
                        else -> R.string.mic_state_busy
                    },
                ),
                onToggle = { if (listening) onReleased() else onPressed() },
            )
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
    pickAnotherLabel: String,
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
        OutlinedButton(onClick = onPickAnother) { Text(pickAnotherLabel) }
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

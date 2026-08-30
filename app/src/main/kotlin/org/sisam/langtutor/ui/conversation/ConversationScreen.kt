package org.sisam.langtutor.ui.conversation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.speech.SpeechRecognizer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.engine.ListeningAck
import org.sisam.langtutor.engine.TurnLatency
import org.sisam.langtutor.speech.PronunciationScore
import org.sisam.langtutor.tutor.Speaker
import org.sisam.langtutor.tutor.TutorMode
import org.sisam.langtutor.tutor.TutorTurnState
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.common.EnglishContent
import org.sisam.langtutor.ui.common.PronunciationFeedback
import org.sisam.langtutor.ui.reward.RewardKind

class ConversationViewModel(
    private val container: AppContainer,
    unitId: String,
) : ViewModel() {

    private val orchestrator = container.createOrchestrator(viewModelScope)

    val state = orchestrator.state
    val transcript = orchestrator.transcript
    val pronunciation = orchestrator.pronunciation

    val handsFreeAvailable = orchestrator.handsFreeAvailable
    fun setHandsFree(enabled: Boolean) { orchestrator.handsFree = enabled }

    /** Gated by BOTH the loaded model tier and the learner's track. */
    val hebrewHelpOffered = orchestrator.hebrewHelpOffered

    fun onHebrewHelp() {
        viewModelScope.launch { orchestrator.onHebrewHelpRequested() }
    }

    init {
        viewModelScope.launch {
            orchestrator.startSession(unitId = unitId, mode = TutorMode.SPEECH)
        }
        // Audio-visual reinforcement lives HERE, not in the composable.
        // Celebrations are events in the session, and the session outlives the
        // composition: driving them from a LaunchedEffect meant every fresh
        // composition of the screen — a rotation, or coming back from the
        // sticker room — replayed the last one.
        viewModelScope.launch {
            // drop(1) skips the StateFlow's replay of its current value; from
            // then on every increment is exactly one finished practice turn.
            orchestrator.turnsCompleted.drop(1).collect {
                container.celebrate(RewardKind.COIN)
            }
        }
        viewModelScope.launch {
            orchestrator.pronunciation.filterNotNull().collect { score ->
                when {
                    // Said it well: the loud, bright cue.
                    score.overall >= GOOD_ATTEMPT -> container.celebrate(RewardKind.STAR)
                    // Nearly: the soft one. Not the same sound, so the
                    // difference is audible without anyone naming it.
                    score.overall >= FAIR_ATTEMPT -> container.celebrate(RewardKind.FLAKE)
                    // Below that, nothing. The coloured phonemes already say
                    // what happened, and a celebration here would be a lie.
                    else -> Unit
                }
            }
        }
    }

    fun onMicPressed() = orchestrator.onMicPressed()
    fun onMicReleased() = orchestrator.onMicReleased()

    fun onTextSubmitted(text: String) {
        viewModelScope.launch { orchestrator.onTextSubmitted(text) }
    }

    override fun onCleared() {
        // Release the multi-GB engine when the conversation screen goes away
        // (thermal/memory budget: nothing stays loaded between sessions).
        orchestrator.shutdown()
    }
}

@Composable
fun ConversationScreen(container: AppContainer, unitId: String) {
    val viewModel: ConversationViewModel = viewModel(
        key = unitId,
        factory = viewModelFactory {
            initializer { ConversationViewModel(container, unitId) }
        },
    )
    val state by viewModel.state.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val pronunciation by viewModel.pronunciation.collectAsState()
    val hebrewHelp by viewModel.hebrewHelpOffered.collectAsState()
    // The gesture block is keyed on handsFree and outlives recompositions,
    // so the state it tests must be read fresh rather than captured.
    val stateNow by rememberUpdatedState(state)
    var draft by remember { mutableStateOf("") }
    var handsFree by remember { mutableStateOf(false) }

    // Hands-free turns end when the VAD says so, not when a finger lifts —
    // the observable moment is the Listening -> Transcribing transition. The
    // clock and the "heard you" blip both belong THERE: marking at the tap
    // (as this screen once did) started the clock before the child had said
    // anything, which counted their whole utterance as our latency.
    var wasListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val listening = state is TutorTurnState.Listening
        if (wasListening && state is TutorTurnState.Transcribing && handsFree) {
            TurnLatency.mark("hands-free endpoint")
            ListeningAck.play()
        }
        wasListening = listening
    }

    // The platform ASR shim needs the mic; ask once when the screen opens.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = A11y.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        // Tuki sits at the top edge beside the title, and moves only while
        // he is actually talking — a pre-reader who cannot follow the
        // transcript still gets an unambiguous "he is speaking to me" cue.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TukiParrot(
                speaking = state is TutorTurnState.Speaking,
                size = A11y.decorativeDp(comfortable = 72, minimum = 44),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.conversation_title),
                    style = if (A11y.hugeText) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                )
                Text(text = stateLabel(state), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Everything below the title scrolls. The status chrome used to be
        // PINNED above the transcript, which at a large font/display size ate
        // the whole viewport and squeezed the conversation itself to nothing;
        // as list items it is visible when the transcript is empty (exactly
        // when it matters) and scrolls away once there is a conversation.
        val context = LocalContext.current
        val speechAvailable = remember {
            // Bundled Whisper counts as speech: the banner is only for devices
            // with NO recognition path at all.
            container.hasBundledAsr || SpeechRecognizer.isRecognitionAvailable(context)
        }
        val transcriptPane: @Composable (Modifier) -> Unit = { paneModifier ->
        LazyColumn(
            modifier = paneModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Honest indicator: is the child talking to the real
                    // on-device model or the scripted demo engine? (real only
                    // when a .litertlm file is present.)
                    Text(
                        text = when {
                            !container.usingRealLlm -> stringResource(R.string.model_mode_demo)
                            // Which tier this session's memory policy actually
                            // loaded — on a busy 12 GB device this is how a
                            // tester spots the E4B->E2B fallback without
                            // pulling logcat.
                            container.modelTierLabel != null ->
                                stringResource(R.string.model_mode_real_tier, container.modelTierLabel!!)
                            else -> stringResource(R.string.model_mode_real)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    // De-googled devices (e.g. GrapheneOS) ship NO speech
                    // recognizer service; without the bundled Whisper model
                    // installed the platform mic path can't work there. Say so
                    // instead of failing silently; typing still works.
                    if (!speechAvailable) {
                        Text(
                            text = stringResource(R.string.speech_unavailable_banner),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // What the engines are doing right now, with a seconds
                    // counter once a step runs long. This is the difference
                    // between a lazy 300 MB load and an apparent freeze.
                    EngineStatusLine()
                    // The first on-device reply includes one-time warm-up and
                    // can take minutes on CPU; without this hint it reads as a
                    // hang.
                    if (state is TutorTurnState.Thinking) {
                        Text(
                            text = stringResource(R.string.thinking_first_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // Debug builds: surface the actual failure reason (the
                    // kid-friendly status label hides it, which made real
                    // errors look like "nothing").
                    val failed = state
                    if (org.sisam.langtutor.BuildConfig.DEBUG && failed is TutorTurnState.Failed) {
                        Text(
                            text = "\u26a0 ${failed.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            items(transcript) { entry ->
                val context = LocalContext.current
                val flaggedNote = stringResource(R.string.report_flagged)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The report mechanism, where the content is: long-
                        // pressing a generated reply flags it for Parent Zone
                        // review. Learner turns need no policing.
                        .pointerInput(entry.text, entry.speaker) {
                            if (entry.speaker == Speaker.TUTOR) {
                                detectTapGestures(onLongPress = {
                                    container.flagReply(entry.text, room = "lesson")
                                    Toast.makeText(context, flaggedNote, Toast.LENGTH_SHORT).show()
                                })
                            }
                        },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (entry.speaker == Speaker.TUTOR) {
                                stringResource(R.string.speaker_tutor)
                            } else {
                                stringResource(R.string.speaker_child)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TranscriptText(entry.text)
                    }
                }
            }
            val thinking = state
            if (thinking is TutorTurnState.Thinking && thinking.partialReply.isNotEmpty()) {
                item {
                    TranscriptText(
                        text = thinking.partialReply,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        }

        val controls: @Composable ColumnScope.() -> Unit = {
        // Per-sound feedback for the last spoken attempt, when the pronunciation
        // coach is installed and the lesson had a phrase to compare against.
        pronunciation?.let { score -> PronunciationFeedback(score) }

        // Hands-free: with the bundled VAD the child taps once and just talks;
        // without it (or when switched off) the button stays hold-to-talk.
        if (viewModel.handsFreeAvailable) {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.conversation_hands_free),
                    style = MaterialTheme.typography.labelMedium,
                )
                Switch(
                    checked = handsFree,
                    onCheckedChange = {
                        handsFree = it
                        viewModel.setHandsFree(it)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                // A tap target, so it shrinks far more gently than the art and
                // never drops under the 48 dp accessibility floor.
                .size(A11y.tapTargetDp(comfortable = 88, minimum = 64))
                .background(
                    if (state is TutorTurnState.Listening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    CircleShape,
                )
                .pointerInput(handsFree) {
                    detectTapGestures(
                        onPress = {
                            // Same rule as the drill room: a press the state
                            // machine refuses must not start the clock, or the
                            // audio already playing closes it with a number
                            // that flatters us and means nothing.
                            val accepted = stateNow is TutorTurnState.AwaitingChild ||
                                stateNow is TutorTurnState.Failed
                            viewModel.onMicPressed()
                            tryAwaitRelease()
                            // Hands-free ignores the release entirely: the VAD
                            // ends the turn, and the LaunchedEffect above marks
                            // that endpoint when it actually happens.
                            if (accepted && !handsFree) {
                                TurnLatency.mark("mic release")
                                // "Heard you", instantly — the reply is seconds
                                // away, the acknowledgement must not be.
                                ListeningAck.play()
                            }
                            if (!handsFree) viewModel.onMicReleased()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "\ud83c\udf99\ufe0f", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            text = stringResource(
                if (handsFree) R.string.conversation_tap_and_talk else R.string.conversation_hold_to_talk,
            ),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // The Hebrew escape hatch. A deliberate tap, not a guess from ASR
        // confidence: the learner is the only one who knows they are lost.
        // Absent entirely on the base tier and for pre-readers — see
        // TutorOrchestrator.hebrewHelpOffered.
        if (hebrewHelp) {
            OutlinedButton(
                onClick = { viewModel.onHebrewHelp() },
                enabled = state is TutorTurnState.AwaitingChild || state is TutorTurnState.Failed,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.conversation_explain_hebrew))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnglishContent {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.conversation_type_hint)) },
                )
            }
            Button(
                onClick = {
                    TurnLatency.mark("send")
                    viewModel.onTextSubmitted(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.conversation_send))
            }
        }
        }

        if (A11y.wideViewport) {
            // Sideways, transcript and controls become columns — height is
            // the scarce axis rotated, and the squeezed vertical stack was
            // the recorded objection (docs/bilingual-gloss.md, Landscape).
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(A11y.sectionGap),
            ) {
                transcriptPane(Modifier.weight(1.2f).fillMaxHeight())
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
                ) {
                    controls()
                }
            }
        } else {
            transcriptPane(Modifier.weight(1f))
            controls()
        }
    }
}

/**
 * A transcript line, whose direction follows its own content.
 *
 * Forcing every line through [EnglishContent] pushed a Hebrew explanation's
 * punctuation to the wrong end. Branching on "does it contain any Hebrew" only
 * moved the problem: a Hebrew explanation that continues in English — which is
 * precisely what the Hebrew-help turn produces — is ONE string with two
 * paragraphs, and a whole-string verdict gets one of them wrong either way.
 *
 * [TextDirection.Content] hands the decision to the BiDi algorithm, which
 * resolves each paragraph from its own first strong character. That is the
 * only rule that is right for all three cases.
 */
@Composable
private fun TranscriptText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        modifier = modifier,
    )
}

@Composable
private fun stateLabel(state: TutorTurnState): String = when (state) {
    TutorTurnState.Idle -> stringResource(R.string.state_idle)
    TutorTurnState.Preparing -> stringResource(R.string.state_preparing)
    TutorTurnState.Listening -> stringResource(R.string.state_listening)
    TutorTurnState.Transcribing -> stringResource(R.string.state_transcribing)
    is TutorTurnState.Thinking -> stringResource(R.string.state_thinking)
    is TutorTurnState.Speaking -> stringResource(R.string.state_speaking)
    is TutorTurnState.AwaitingChild -> stringResource(R.string.state_your_turn)
    is TutorTurnState.Failed -> stringResource(R.string.state_failed)
}

/** Bright cue at or above this; soft cue above [FAIR_ATTEMPT]; silence below. */
private const val GOOD_ATTEMPT = 0.8f
private const val FAIR_ATTEMPT = 0.5f

package org.sisam.langtutor.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.speech.SpeechRecognizer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.speech.PronunciationScore
import org.sisam.langtutor.tutor.Speaker
import org.sisam.langtutor.tutor.TutorMode
import org.sisam.langtutor.tutor.TutorTurnState
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.common.EnglishContent

class ConversationViewModel(container: AppContainer, unitId: String) : ViewModel() {

    private val orchestrator = container.createOrchestrator(viewModelScope)

    val state = orchestrator.state
    val transcript = orchestrator.transcript
    val pronunciation = orchestrator.pronunciation

    val handsFreeAvailable = orchestrator.handsFreeAvailable
    fun setHandsFree(enabled: Boolean) { orchestrator.handsFree = enabled }

    init {
        viewModelScope.launch {
            orchestrator.startSession(unitId = unitId, mode = TutorMode.SPEECH)
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
    var draft by remember { mutableStateOf("") }
    var handsFree by remember { mutableStateOf(false) }

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                size = 72.dp,
            )
            Text(
                text = stringResource(R.string.conversation_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        Text(text = stateLabel(state), style = MaterialTheme.typography.bodyMedium)
        // Honest indicator: is the child talking to the real on-device model or
        // the scripted demo engine? (real only when a .litertlm file is present.)
        Text(
            text = when {
                !container.usingRealLlm -> stringResource(R.string.model_mode_demo)
                // Which tier this session's memory policy actually loaded —
                // on a busy 12 GB device this is how a tester spots the
                // E4B→E2B fallback without pulling logcat.
                container.modelTierLabel != null ->
                    stringResource(R.string.model_mode_real_tier, container.modelTierLabel!!)
                else -> stringResource(R.string.model_mode_real)
            },
            style = MaterialTheme.typography.labelSmall,
        )
        // De-googled devices (e.g. GrapheneOS) ship NO speech recognizer service;
        // without the bundled Whisper model installed the platform mic path can't
        // work there. Say so instead of failing silently; typing still works.
        val context = LocalContext.current
        val speechAvailable = remember {
            // Bundled Whisper counts as speech: the banner is only for devices
            // with NO recognition path at all.
            container.hasBundledAsr || SpeechRecognizer.isRecognitionAvailable(context)
        }
        if (!speechAvailable) {
            Text(
                text = stringResource(R.string.speech_unavailable_banner),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // What the engines are doing right now, with a seconds counter once a
        // step runs long. This is the difference between a lazy 300 MB load and
        // an apparent freeze.
        EngineStatusLine()
        // The first on-device reply includes one-time warm-up and can take
        // minutes on CPU; without this hint it reads as a hang.
        if (state is TutorTurnState.Thinking) {
            Text(
                text = stringResource(R.string.thinking_first_hint),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // Debug builds: surface the actual failure reason (the kid-friendly
        // status label hides it, which made real errors look like "nothing").
        val failed = state
        if (org.sisam.langtutor.BuildConfig.DEBUG && failed is TutorTurnState.Failed) {
            Text(
                text = "⚠ ${failed.reason}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(transcript) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (entry.speaker == Speaker.TUTOR) {
                                stringResource(R.string.speaker_tutor)
                            } else {
                                stringResource(R.string.speaker_child)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        EnglishContent {
                            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            val thinking = state
            if (thinking is TutorTurnState.Thinking && thinking.partialReply.isNotEmpty()) {
                item {
                    EnglishContent {
                        Text(
                            text = thinking.partialReply,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

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
                .size(88.dp)
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
                            viewModel.onMicPressed()
                            tryAwaitRelease()
                            // Hands-free ignores the release; the VAD ends the turn.
                            if (!handsFree) viewModel.onMicReleased()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🎙️", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            text = stringResource(
                if (handsFree) R.string.conversation_tap_and_talk else R.string.conversation_hold_to_talk,
            ),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

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
                    viewModel.onTextSubmitted(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.conversation_send))
            }
        }
    }
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

/**
 * Per-sound result of the last attempt: each expected sound coloured by how
 * confidently the model heard it. Deliberately wordless — a 5-year-old reads
 * colours, not scores (docs/mockups/pronunciation.html).
 */
@Composable
private fun PronunciationFeedback(score: PronunciationScore) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.pronunciation_title),
                style = MaterialTheme.typography.labelMedium,
            )
            EnglishContent {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    score.phonemes.take(MAX_SHOWN).forEach { p ->
                        Text(
                            text = p.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                p.score >= 0.8f -> Color(0xFF2E7D32) // green: said well
                                p.score >= 0.4f -> Color(0xFFEF6C00) // amber: nearly
                                else -> Color(0xFFC62828) // red: try again
                            },
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.pronunciation_stars,
                    (score.overall * 5).toInt().coerceIn(1, 5),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_SHOWN = 24

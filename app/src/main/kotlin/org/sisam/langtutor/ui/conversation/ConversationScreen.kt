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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.tutor.Speaker
import org.sisam.langtutor.tutor.TutorMode
import org.sisam.langtutor.tutor.TutorTurnState
import org.sisam.langtutor.ui.common.EnglishContent

class ConversationViewModel(container: AppContainer) : ViewModel() {

    private val orchestrator = container.createOrchestrator(viewModelScope)

    val state = orchestrator.state
    val transcript = orchestrator.transcript

    init {
        viewModelScope.launch {
            orchestrator.startSession(unitId = "unit-001", mode = TutorMode.SPEECH)
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
fun ConversationScreen(container: AppContainer) {
    val viewModel: ConversationViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConversationViewModel(container) }
        },
    )
    val state by viewModel.state.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    var draft by remember { mutableStateOf("") }

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
        Text(
            text = stringResource(R.string.conversation_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = stateLabel(state), style = MaterialTheme.typography.bodyMedium)
        // Honest indicator: is the child talking to the real on-device model or
        // the scripted demo engine? (real only when a .litertlm file is present.)
        Text(
            text = stringResource(
                if (container.usingRealLlm) R.string.model_mode_real else R.string.model_mode_demo,
            ),
            style = MaterialTheme.typography.labelSmall,
        )
        // De-googled devices (e.g. GrapheneOS) ship NO speech recognizer service:
        // the platform mic path can't work there until the bundled Whisper ASR
        // lands. Say so instead of failing silently; typing still works.
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

        // Push-to-talk: press starts capture, release finishes the turn.
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            viewModel.onMicPressed()
                            tryAwaitRelease()
                            viewModel.onMicReleased()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🎙️", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            text = stringResource(R.string.conversation_hold_to_talk),
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

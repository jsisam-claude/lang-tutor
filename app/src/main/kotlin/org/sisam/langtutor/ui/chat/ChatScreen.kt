package org.sisam.langtutor.ui.chat

import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.tutor.chat.ChatEntry
import org.sisam.langtutor.tutor.chat.ChatSpeaker
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.ParrotPalette
import org.sisam.langtutor.ui.common.TukiParrot

/**
 * "Just chat" — a messenger-style three-way room: the learner plus Tuki and
 * Kiki. Learner bubbles sit at the end (right in LTR), each parrot's bubbles
 * at the start with its own avatar; the avatar of whichever parrot is talking
 * animates, and a typing bubble shows while one is generating.
 *
 * Input is dual-channel like the lesson rooms: type, or hold the mic and
 * talk. The trailing control swaps the way every messenger's does — mic when
 * the draft is empty, Send once there is text — because this room serves
 * adults and teens as much as children, and that is the muscle memory they
 * arrive with. A spoken turn is sent as soon as it is transcribed: replying
 * to what was actually heard is the honest shape of speaking practice.
 */
@Composable
fun ChatScreen(container: AppContainer) {
    val room = remember { container.createChatRoom() }
    val scope = rememberCoroutineScope()
    val messages by room.messages.collectAsState()
    val typing by room.typing.collectAsState()
    val speaking by room.speaking.collectAsState()
    val busy by room.busy.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The mic. Same singleton-backed engine as the lesson rooms; hidden
    // entirely when the device has no recognition path (typing still works).
    val asr = remember { container.createAsrEngine() }
    val context = LocalContext.current
    val speechAvailable = remember {
        container.hasBundledAsr || SpeechRecognizer.isRecognitionAvailable(context)
    }
    var micState by remember { mutableStateOf(MicState.IDLE) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (speechAvailable) micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) { room.start() }
    DisposableEffect(Unit) {
        onDispose {
            // Same thermal doctrine as lessons: nothing stays loaded between
            // screens. Also puts the parent-picked voice back — the room sets
            // a voice per speaker.
            container.appScope.launch {
                // A capture the learner walked out on must not hold the mic.
                runCatching { asr.stopCapture() }
                room.shutdown()
            }
            container.reapplyChosenVoice()
        }
    }
    LaunchedEffect(messages.size, typing) {
        val count = messages.size + if (typing != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: both parrots, like a group-chat title row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = A11y.gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val avatar = A11y.decorativeDp(comfortable = 44, minimum = 30)
            TukiParrot(speaking = speaking == ChatSpeaker.TUKI, size = avatar)
            TukiParrot(
                speaking = speaking == ChatSpeaker.KIKI,
                size = avatar,
                palette = ParrotPalette.KIKI,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.chat_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages) { entry -> ChatBubble(entry) }
            if (typing != null) {
                items(listOf(typing!!)) { who ->
                    ChatBubble(ChatEntry(who, "…"))
                }
            }
        }

        // While the mic is doing something, say so where the reply would
        // appear — the room has no status line of its own.
        if (micState != MicState.IDLE) {
            Text(
                text = stringResource(
                    if (micState == MicState.LISTENING) R.string.state_listening else R.string.state_transcribing,
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = A11y.gutter),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_hint)) },
            )
            // Gate on busy, not typing: typing clears when generation ends,
            // but the room refuses input until the audio has finished too —
            // an enabled control whose input is silently dropped is worse
            // than a briefly disabled one.
            if (draft.isNotBlank() || !speechAvailable) {
                Button(
                    onClick = {
                        val text = draft
                        draft = ""
                        scope.launch { room.send(text) }
                    },
                    enabled = draft.isNotBlank() && !busy,
                ) {
                    Text(stringResource(R.string.chat_send))
                }
            } else {
                ChatMic(
                    enabled = !busy,
                    state = micState,
                    onStateChange = { micState = it },
                    onHeard = { text -> scope.launch { room.send(text) } },
                    asr = asr,
                )
            }
        }
    }
}

private enum class MicState { IDLE, LISTENING, TRANSCRIBING }

/**
 * Hold-to-talk, WhatsApp-shaped: press and hold, speak, release to send.
 * The transcript goes straight into the room — no confirm step, because the
 * point of the room is conversational flow, and the child bubble shows
 * exactly what was heard.
 */
@Composable
private fun ChatMic(
    enabled: Boolean,
    state: MicState,
    onStateChange: (MicState) -> Unit,
    onHeard: (String) -> Unit,
    asr: org.sisam.langtutor.speech.AsrEngine,
) {
    val scope = rememberCoroutineScope()
    // Read through rememberUpdatedState so a mid-press recomposition (busy
    // flipping) cannot restart the gesture and orphan a running capture.
    val enabledNow by rememberUpdatedState(enabled)
    val stateNow by rememberUpdatedState(state)

    Box(
        modifier = Modifier
            .size(A11y.tapTargetDp(comfortable = 56, minimum = 48))
            .background(
                when {
                    state == MicState.LISTENING -> MaterialTheme.colorScheme.error
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                CircleShape,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!enabledNow || stateNow != MicState.IDLE) return@detectTapGestures
                        onStateChange(MicState.LISTENING)
                        val started = scope.launch {
                            runCatching { asr.startCapture(RecognitionHint.None) }
                        }
                        tryAwaitRelease()
                        scope.launch {
                            // Never stop before start has finished — on the
                            // first ever press start may be loading Whisper.
                            started.join()
                            onStateChange(MicState.TRANSCRIBING)
                            val result = runCatching { asr.stopCapture() }.getOrNull()
                            onStateChange(MicState.IDLE)
                            val text = result?.transcript?.trim().orEmpty()
                            if (text.isNotEmpty()) onHeard(text)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state == MicState.TRANSCRIBING) "…" else "🎙️",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val fromChild = entry.speaker == ChatSpeaker.CHILD
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromChild) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromChild) {
            TukiParrot(
                speaking = false,
                size = A11y.decorativeDp(comfortable = 28, minimum = 20),
                palette = if (entry.speaker == ChatSpeaker.KIKI) {
                    ParrotPalette.KIKI
                } else {
                    ParrotPalette.TUKI
                },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                // Fraction of the real viewport, not a fixed 300.dp: a large
                // display-size setting can shrink the screen below that cap,
                // and then every bubble ran off the edge.
                .widthIn(max = A11y.bubbleMaxWidth)
                .background(
                    color = when {
                        fromChild -> MaterialTheme.colorScheme.primaryContainer
                        entry.speaker == ChatSpeaker.KIKI ->
                            MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (fromChild) 14.dp else 3.dp,
                        bottomEnd = if (fromChild) 3.dp else 14.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

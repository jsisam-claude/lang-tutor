package org.sisam.langtutor.ui.chat

import android.speech.SpeechRecognizer
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.engine.ListeningAck
import org.sisam.langtutor.engine.TurnLatency
import org.sisam.langtutor.speech.RecognitionHint
import org.sisam.langtutor.tutor.chat.ChatEntry
import org.sisam.langtutor.tutor.chat.ChatSpeaker
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.common.TukiParrot

/**
 * "Just chat" — a messenger-style room: the learner and Tuki. Learner bubbles
 * sit at the end (right in LTR), Tuki's at the start with his avatar; the
 * avatar animates while he is talking, and a typing bubble shows while he is
 * generating.
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
        // Off the main thread: the first press otherwise pays the Whisper
        // interpreter load inside the turn — the container's preload covers
        // this only when the parent pressed that button.
        withContext(Dispatchers.IO) {
            runCatching { asr.warmUp() }
            // A few KB of static PCM; built now so the first turn's "heard
            // you" blip is as instant as every later one.
            runCatching { ListeningAck.warmUp() }
        }
    }

    LaunchedEffect(Unit) { room.start() }
    DisposableEffect(Unit) {
        onDispose {
            // Same thermal doctrine as lessons: nothing stays loaded between
            // screens.
            container.appScope.launch {
                // A capture the learner walked out on must not hold the mic.
                runCatching { asr.stopCapture() }
                room.shutdown()
            }
        }
    }
    LaunchedEffect(messages.size, typing) {
        val count = messages.size + if (typing != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header: Tuki and the room name, like a chat title row.
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
            items(messages) { entry -> ChatBubble(entry, container) }
            if (typing != null) {
                items(listOf(typing!!)) { who ->
                    ChatBubble(ChatEntry(who, "…"), container)
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
        // "Heard so far": the soft-endpoint speculation, surfaced live. A
        // guess, quoted and muted — the sent message stays the final result.
        var heard by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(micState == MicState.LISTENING) {
            heard = null
            if (micState == MicState.LISTENING) {
                asr.speculative.collect { heard = it }
            }
        }
        if (micState == MicState.LISTENING && !heard.isNullOrBlank()) {
            Text(
                text = "“$heard”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        TurnLatency.mark("chat send")
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
                    onHeard = { text ->
                        // Clock already running: marked at mic release.
                        scope.launch { room.send(text) }
                    },
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
                        // The learner-felt clock starts HERE. It was marked in
                        // onHeard — after stopCapture returned — so the chat
                        // metric silently excluded the entire Whisper decode,
                        // while the lesson room counted it. Same name, two
                        // meanings: the worst kind of number.
                        TurnLatency.mark("chat mic release")
                        // "Heard you" — instant, before ASR has even returned.
                        // The felt wait starts here; the blip is what stops it
                        // feeling like being ignored (docs/latency.md item 3).
                        ListeningAck.play()
                        scope.launch {
                            // Never stop before start has finished — on the
                            // first ever press start may be loading Whisper.
                            started.join()
                            onStateChange(MicState.TRANSCRIBING)
                            val result = runCatching { asr.stopCapture() }.getOrNull()
                            onStateChange(MicState.IDLE)
                            val text = result?.transcript?.trim().orEmpty()
                            // A silent press produces no turn and must not
                            // leave a stale mark for the NEXT play() to close
                            // with a nonsense number.
                            if (text.isNotEmpty()) onHeard(text) else TurnLatency.clear()
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
private fun ChatBubble(entry: ChatEntry, container: AppContainer) {
    val fromChild = entry.speaker == ChatSpeaker.CHILD
    val context = LocalContext.current
    val flaggedNote = stringResource(R.string.report_flagged)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromChild) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromChild) {
            TukiParrot(
                speaking = false,
                size = A11y.decorativeDp(comfortable = 28, minimum = 20),
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
                    color = if (fromChild) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (fromChild) 14.dp else 3.dp,
                        bottomEnd = if (fromChild) 3.dp else 14.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                // The report mechanism, where the content is: long-pressing a
                // generated reply flags it for Parent Zone review. Only
                // Tuki's bubbles — the learner's own words need no policing.
                .pointerInput(entry.text, fromChild) {
                    if (!fromChild) {
                        detectTapGestures(onLongPress = {
                            container.flagReply(entry.text, room = "chat")
                            Toast.makeText(context, flaggedNote, Toast.LENGTH_SHORT).show()
                        })
                    }
                },
        ) {
            // Tuki's lines get the pronunciation key; the learner's own words
            // do not. They already know how they said it — what they need help
            // reading is the reply.
            val gloss by rememberGloss(container, if (fromChild) "" else entry.text)
            if (gloss.isEmpty() && entry.hebrew == null) {
                Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
            } else if (gloss.isEmpty()) {
                // Meaning without the pronunciation key: still a stacked pair,
                // just with nothing in the middle row.
                GlossedText(
                    words = listOf(GlossWord(entry.text, "")),
                    style = MaterialTheme.typography.bodyLarge,
                    horizontalArrangement = Arrangement.Start,
                    translation = entry.hebrew,
                )
            } else {
                GlossedText(
                    words = gloss,
                    style = MaterialTheme.typography.bodyLarge,
                    glossStyle = MaterialTheme.typography.bodyMedium,
                    horizontalArrangement = Arrangement.Start,
                    translation = entry.hebrew,
                )
            }
        }
    }
}

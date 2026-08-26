package org.sisam.langtutor.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
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
 */
@Composable
fun ChatScreen(container: AppContainer) {
    val room = remember { container.createChatRoom() }
    val scope = rememberCoroutineScope()
    val messages by room.messages.collectAsState()
    val typing by room.typing.collectAsState()
    val speaking by room.speaking.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { room.start() }
    DisposableEffect(Unit) {
        onDispose {
            // Same thermal doctrine as lessons: nothing stays loaded between
            // screens. Also puts the parent-picked voice back — the room sets
            // a voice per speaker.
            container.appScope.launch { room.shutdown() }
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
            Button(
                onClick = {
                    val text = draft
                    draft = ""
                    scope.launch { room.send(text) }
                },
                enabled = draft.isNotBlank() && typing == null,
            ) {
                Text(stringResource(R.string.chat_send))
            }
        }
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

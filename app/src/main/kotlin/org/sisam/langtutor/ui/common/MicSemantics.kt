package org.sisam.langtutor.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * Makes a hold-to-talk mic reachable without touch.
 *
 * Every mic in the app is a [androidx.compose.foundation.layout.Box] with a
 * raw `detectTapGestures`, which is the right primitive for press-and-hold and
 * is completely invisible to the accessibility tree: TalkBack cannot find it,
 * Switch Access cannot scan to it, and a keyboard cannot reach it. The rooms
 * built around that mic — the drill, the twisters, the conversation, the chat
 * — were unusable by anyone not using a touchscreen. The app's stated
 * accessibility rule is "decoration yields, controls never do", and this is
 * the one control that had been yielding.
 *
 * The fix cannot be `clickable` or `toggleable`: those bring their own pointer
 * handling, which would fire alongside the gesture detector and start the mic
 * twice on one tap. This adds NO touch behaviour at all — only the semantics
 * an assistive service reads, plus keyboard focus and Enter/Space.
 *
 * A press-and-hold has no equivalent for a service that can only "activate" a
 * control, so the accessible form is a TOGGLE: activate to start listening,
 * activate again to stop. The rooms already end a turn on their own — the
 * drill on an exact match, the conversation on the VAD endpoint — so a learner
 * who activates once and simply speaks is served either way.
 */
fun Modifier.micSemantics(
    listening: Boolean,
    enabled: Boolean,
    /** What the control is, e.g. "Microphone". */
    label: String,
    /** What activating it does right now, e.g. "Start talking". */
    actionLabel: String,
    /** Whether it is listening, spoken by the screen reader on every change. */
    state: String,
    onToggle: () -> Unit,
): Modifier = this
    .focusable()
    .onKeyEvent { event ->
        val activate = event.key == Key.Enter || event.key == Key.Spacebar ||
            event.key == Key.NumPadEnter
        if (enabled && activate && event.type == KeyEventType.KeyUp) {
            onToggle()
            true
        } else {
            false
        }
    }
    .semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = label
        stateDescription = state
        if (!enabled) disabled()
        onClick(label = actionLabel) {
            if (enabled) onToggle()
            enabled
        }
    }

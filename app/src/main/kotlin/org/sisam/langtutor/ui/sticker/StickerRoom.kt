package org.sisam.langtutor.ui.sticker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.SkyBackdrop
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.reward.RewardKind

/**
 * The sticker room: a young learner's payoff for a stretch of work.
 *
 * Deliberately a whole screen rather than a dialog. A pre-reader needs the
 * reward to be a PLACE they arrive at, not a box that appears over the thing
 * they were already doing — and arriving somewhere is also what makes leaving
 * it feel like going back to work.
 *
 * There is no confirm button and no way to get it wrong: every sticker is a
 * good choice, tapping one takes it, and the room returns to the lesson on its
 * own a beat later. A child who cannot read cannot be asked to press "Done".
 */
@Composable
fun StickerRoom(container: AppContainer, onDone: () -> Unit) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    var picked by remember { mutableStateOf<String?>(null) }

    // One trip through the room per visit: once a sticker is taken the screen
    // is on rails to the exit, and a second tap must not stack another return.
    LaunchedEffect(picked) {
        val id = picked ?: return@LaunchedEffect
        container.profile.update { it.copy(stickers = it.stickers + id) }
        container.celebrate(RewardKind.MIX)
        delay(RETURN_DELAY_MS)
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = A11y.gutter, vertical = A11y.sectionGap),
            verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TukiParrot(
                speaking = picked != null,
                size = A11y.decorativeDp(comfortable = 84, minimum = 48),
            )
            Text(
                text = stringResource(
                    if (picked == null) R.string.sticker_pick_one else R.string.sticker_taken,
                ),
                style = if (A11y.hugeText) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                textAlign = TextAlign.Center,
            )

            val tile = A11y.decorativeDp(comfortable = 96, minimum = 64)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (sticker in STICKER_BOOK) {
                    // The chosen one swells and everything else recedes, so the
                    // choice is legible without a word of text.
                    val scale by animateFloatAsState(
                        targetValue = when (picked) {
                            null -> 1f
                            sticker.id -> 1.35f
                            else -> 0.72f
                        },
                        animationSpec = tween(durationMillis = 320),
                        label = "sticker-${sticker.id}",
                    )
                    StickerFace(
                        sticker = sticker,
                        size = tile,
                        modifier = Modifier
                            .scale(scale)
                            .clickable(enabled = picked == null) { picked = sticker.id },
                    )
                }
            }

            if (profile.stickers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.sticker_collection, profile.stickers.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                // The shelf. Seeing the pile grow is the durable half of the
                // reward — the burst lasts two seconds, this lasts.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (id in profile.stickers.takeLast(SHELF_MAX)) {
                        stickerById(id)?.let { StickerFace(it, size = tile / 2.4f) }
                    }
                }
            }
        }
    }
}

/** Long enough to see the burst land, short enough not to become a screen the
 *  child sits in. */
private const val RETURN_DELAY_MS = 1_800L

/** The shelf shows the most recent stickers only; a hundred of them is a wall. */
private const val SHELF_MAX = 24

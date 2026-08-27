package org.sisam.langtutor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.speech.HebrewText
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord

/**
 * The Hebrew pronunciation of [text], or an empty list when this learner's
 * settings say no — and, briefly, while the first call warms the dictionary.
 *
 * Empty-means-not-yet is deliberate rather than a nullable "loading" state:
 * every call site draws the plain English line when the list is empty, so a
 * cold start shows the lesson at once and the gloss appears under it a moment
 * later. Blocking the sentence on its pronunciation key would be exactly the
 * wrong trade — the words are the content, the gloss is the help.
 *
 * A line that already contains Hebrew is never glossed. The Hebrew-help turn
 * produces exactly that — Hebrew and English in one string — and glossing it
 * would either transliterate Hebrew as though it were English or leave a row
 * of gaps under half the words. Both look like a bug to the reader, and the
 * one thing a pronunciation key must never be is untrustworthy.
 */
@Composable
fun rememberGloss(container: AppContainer, text: String): State<List<GlossWord>> {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val enabled = container.glossEnabled(profile)
    // Keyed on the setting as well as the text: toggling it in Parent Zone has
    // to take effect on the screen behind, without a round change.
    return produceState(initialValue = emptyList(), text, enabled) {
        value = if (enabled && text.isNotBlank() && !HebrewText.contains(text)) {
            container.transliterate(text)
        } else {
            emptyList()
        }
    }
}

/**
 * [hebrew] if this learner's settings show translations, else null.
 *
 * A one-line gate rather than a call site's `if`, so "should the meaning be
 * on screen" is answered in exactly one place for every room. The text itself
 * is never produced here — it comes authored from the curriculum or from the
 * turn that generated the line, both of which have already vetted it.
 */
@Composable
fun rememberTranslation(container: AppContainer, hebrew: String?): State<String?> {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val enabled = container.translationEnabled(profile)
    return produceState<String?>(initialValue = null, hebrew, enabled) {
        value = hebrew?.takeIf { enabled && it.isNotBlank() }
    }
}

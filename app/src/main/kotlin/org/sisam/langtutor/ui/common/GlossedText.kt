package org.sisam.langtutor.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.content.AlignCue
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.tutor.drill.AlignHighlight

/**
 * An English line with its Hebrew-letter pronunciation stacked underneath,
 * word by word.
 *
 * ```
 *   I        see       a      lion
 *   אַי      סִי       אֶ      לַיאֶן      <- sounds, aligned per word
 *   אני רואה אריה                         <- meaning, one natural sentence
 * ```
 *
 * The two Hebrew rows are different KINDS of thing and are laid out
 * differently on purpose. The pronunciation is per word, so it stacks in
 * columns. The translation is a sentence — `a lion` is two English words and
 * one Hebrew one — so forcing it into the same columns would either lie about
 * the correspondence or wreck the Hebrew word order. It gets its own line,
 * right-to-left, reading as ordinary Hebrew.
 *
 * ## Why this is a column per word and not two lines of text
 *
 * Hebrew runs right-to-left and English left-to-right, so setting each row in
 * its own natural direction puts the first English word above the *last*
 * Hebrew one and nothing lines up. The fix is the one printed interlinear
 * texts use: lay the WORDS out in English order and let each Hebrew word be
 * internally RTL. So the row stays [LayoutDirection.Ltr] and only the Hebrew
 * cell flips — per cell, which is what [EnglishContent] does not do.
 *
 * [FlowRow] rather than a wrapping [Text] for the same reason: it breaks
 * between items, never inside one, so a long line wraps as whole word-columns
 * and a pairing can never be split across two lines. A gloss that has drifted
 * one column is worse than no gloss, because the reader has no way to tell.
 */
@Composable
fun GlossedText(
    words: List<GlossWord>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    glossStyle: TextStyle = MaterialTheme.typography.titleMedium,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    translation: String? = null,
    /** Karaoke: index of the word being SPOKEN right now — bolded and tinted
     *  so the eye rides the voice. */
    highlightWordIndex: Int? = null,
    /** Post-attempt karaoke: indices the last attempt missed, marked in the
     *  error colour so a retry has somewhere to aim. */
    missedWords: Set<Int> = emptySet(),
    /**
     * Phrasebank alignment for [translation] (docs/phrasebank.md): while
     * karaoke bolds an English word, the Hebrew words that MEAN it bold too —
     * inside the naturally-ordered Hebrew line, nothing rearranged. Null (no
     * cues authored for this line) leaves the meaning row unlit.
     */
    translationCues: List<AlignCue>? = null,
    /**
     * Meaning support for lines that CANNOT carry a trustworthy translation:
     * words in the curated picture set (objects, verbs, emotions) show their
     * small icon under the word column — a picture is a translation that
     * cannot lie. Callers turn this on exactly where the meaning row is
     * absent, so the two mechanisms never compete for the same eye.
     */
    showWordIcons: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = when (horizontalArrangement) {
            Arrangement.Center -> Alignment.CenterHorizontally
            else -> Alignment.Start
        },
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((index, word) in words.withIndex()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Breathing room between columns; without it the eye pairs
                    // a Hebrew word with the wrong neighbour at small sizes.
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = word.english,
                        style = when {
                            index == highlightWordIndex ->
                                style.copy(fontWeight = FontWeight.Bold)
                            else -> style
                        },
                        color = when {
                            index == highlightWordIndex -> MaterialTheme.colorScheme.primary
                            index in missedWords -> MaterialTheme.colorScheme.error
                            else -> Color.Unspecified
                        },
                        textAlign = TextAlign.Center,
                    )
                    if (word.hebrew.isNotEmpty()) {
                        // The one place the direction flips back. Hebrew inside
                        // an LTR row renders correctly only if the cell says so.
                        CompositionLocalProvider(
                            LocalLayoutDirection provides LayoutDirection.Rtl,
                        ) {
                            Text(
                                text = word.hebrew,
                                style = glossStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (showWordIcons) {
                        wordIcon(word.english)?.let { id ->
                            Image(
                                painter = painterResource(id),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
        if (!translation.isNullOrBlank()) {
            // Natural Hebrew, not a column: RTL for the whole line, and its
            // own direction scope so it is unaffected by the LTR row above.
            // With cues, the words MEANING the currently-sounding English
            // word bold in place — emphasis moves, order never does.
            val lit = if (translationCues != null && highlightWordIndex != null) {
                AlignHighlight.hebrewWordsFor(highlightWordIndex, translationCues)
            } else {
                emptySet()
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    text = buildAnnotatedString {
                        append(translation)
                        if (lit.isNotEmpty()) {
                            // Word char-offsets by the same split(" ") rule the
                            // cues were authored against; an index past the
                            // actual word count is dropped, never shifted.
                            var start = 0
                            translation.split(" ").forEachIndexed { i, w ->
                                if (i in lit) {
                                    addStyle(
                                        SpanStyle(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        ),
                                        start, start + w.length,
                                    )
                                }
                                start += w.length + 1
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The curated-art lookup for [GlossedText]'s icon row: punctuation stripped,
 * case folded, one conservative plural strip ("apples" finds apple). No
 * stemming beyond that — a wrong icon under a word teaches a wrong meaning,
 * which is worse than none.
 *
 * [AMBIGUOUS] is the same principle at the word level. These words have art
 * (the picture room shows it, where a card names its own subject), but in a
 * SENTENCE they carry a sense the picture would contradict: every "orange"
 * in the bank is the colour, not the fruit, "square" is the town square more
 * often than the shape, and "one" is nearly always the determiner ("one
 * more", "the little one") rather than the numeral its card draws. An icon
 * that is right half the time is not half-useful — it is a lie the learner
 * cannot check. two/three/four stay: they are reliably numeric.
 */
private val AMBIGUOUS = setOf("orange", "square", "one")

private fun wordIcon(raw: String): Int? {
    val w = raw.trim { !it.isLetter() }.lowercase()
    if (w.isEmpty() || w in AMBIGUOUS) return null
    return org.sisam.langtutor.ui.picture.PictureArt.drawableFor(w)
        ?: w.removeSuffix("s").takeIf { it != w && it !in AMBIGUOUS }
            ?.let { org.sisam.langtutor.ui.picture.PictureArt.drawableFor(it) }
}

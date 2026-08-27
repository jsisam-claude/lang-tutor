package org.sisam.langtutor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord

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
            for (word in words) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Breathing room between columns; without it the eye pairs
                    // a Hebrew word with the wrong neighbour at small sizes.
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Text(text = word.english, style = style, textAlign = TextAlign.Center)
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
                }
            }
        }
    }
        if (!translation.isNullOrBlank()) {
            // Natural Hebrew, not a column: RTL for the whole line, and its
            // own direction scope so it is unaffected by the LTR row above.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                    text = translation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

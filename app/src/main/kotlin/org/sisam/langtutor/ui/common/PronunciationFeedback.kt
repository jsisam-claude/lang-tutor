package org.sisam.langtutor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.R
import org.sisam.langtutor.speech.PronunciationScore

/**
 * Per-sound result of the last attempt: each expected sound coloured by how
 * confidently the model heard it. Deliberately wordless for the learner — a
 * 5-year-old reads colours, not scores (docs/mockups/pronunciation.html).
 * Shared by the conversation screen and the vocabulary room.
 *
 * Wordless is not the same as colour-only, which is what this was. Colour
 * carried the ENTIRE message, so the card said nothing at all to a
 * colour-blind learner, in greyscale, or through a screen reader. Each symbol
 * now also carries a mark beneath it and a spoken description, and the row
 * wraps so a long line no longer hides the sounds past the twenty-fourth.
 */
@Composable
fun PronunciationFeedback(score: PronunciationScore) {
    val goodLabel = stringResource(R.string.pronunciation_good)
    val nearlyLabel = stringResource(R.string.pronunciation_nearly)
    val againLabel = stringResource(R.string.pronunciation_again)
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
                // WRAPS, and shows every sound. A fixed Row silently cut the
                // list at 24 while the score below averaged all of them, so a
                // long line hid exactly the sounds a learner most needed to
                // see — and hid them off the end of a row that did not scroll.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    score.phonemes.forEach { p ->
                        val good = p.score >= 0.8f
                        val nearly = p.score >= 0.4f
                        Text(
                            // Colour is never the only channel: a mark under
                            // the symbol says the same thing, so this reads
                            // for a colour-blind learner and in greyscale.
                            text = p.symbol + when {
                                good -> ""
                                nearly -> "\u0331"
                                else -> "\u0332"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                good -> Color(0xFF2E7D32) // green: said well
                                nearly -> Color(0xFFEF6C00) // amber: nearly
                                else -> Color(0xFFC62828) // red: try again
                            },
                            modifier = Modifier.semantics {
                                contentDescription = p.symbol + ", " + when {
                                    good -> goodLabel
                                    nearly -> nearlyLabel
                                    else -> againLabel
                                }
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



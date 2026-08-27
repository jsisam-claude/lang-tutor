package org.sisam.langtutor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sisam.langtutor.R
import org.sisam.langtutor.speech.PronunciationScore

/**
 * Per-sound result of the last attempt: each expected sound coloured by how
 * confidently the model heard it. Deliberately wordless — a 5-year-old reads
 * colours, not scores (docs/mockups/pronunciation.html). Shared by the
 * conversation screen and the vocabulary room, which show the same coach.
 */
@Composable
fun PronunciationFeedback(score: PronunciationScore) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    score.phonemes.take(MAX_SHOWN).forEach { p ->
                        Text(
                            text = p.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                p.score >= 0.8f -> Color(0xFF2E7D32) // green: said well
                                p.score >= 0.4f -> Color(0xFFEF6C00) // amber: nearly
                                else -> Color(0xFFC62828) // red: try again
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

private const val MAX_SHOWN = 24

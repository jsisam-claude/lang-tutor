package org.sisam.langtutor.ui.cloze

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.PhraseTheme
import org.sisam.langtutor.content.PicturePack
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.tutor.cloze.ClozeDeck
import org.sisam.langtutor.tutor.cloze.ClozeEvent
import org.sisam.langtutor.tutor.cloze.ClozeItem
import org.sisam.langtutor.tutor.cloze.ClozeKind
import org.sisam.langtutor.tutor.cloze.ClozeOutcome
import org.sisam.langtutor.tutor.cloze.ClozeSource
import org.sisam.langtutor.tutor.cloze.ClozeState
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.EnglishContent
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.picture.PictureArt
import org.sisam.langtutor.ui.picture.PictureArtView
import org.sisam.langtutor.ui.reward.RewardKind

class ClozeViewModel(
    private val container: AppContainer,
    private val source: ClozeSource,
) : ViewModel() {

    private val room = container.createCloze(viewModelScope)
    val state = room.state

    /** Sentence id → the gap it wore last time this session, so a line
     *  that comes round again blanks a different word. */
    private val recent = mutableMapOf<String, Int>()

    init {
        viewModelScope.launch { startRound() }
        viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is ClozeEvent.Resolved -> when (event.outcome) {
                        ClozeOutcome.FIRST_TRY -> container.celebrate(RewardKind.STAR)
                        ClozeOutcome.FOUND -> container.celebrate(RewardKind.FLAKE)
                        // Found by elimination: the line is read, nothing bursts.
                        ClozeOutcome.SHOWN -> Unit
                    }
                    ClozeEvent.Wrong -> Unit
                    is ClozeEvent.RoundDone -> if (event.total > 0) container.celebrate(RewardKind.MIX)
                }
            }
        }
    }

    private suspend fun startRound() {
        val level = container.profile.snapshot().effectiveLevel
        val items = runCatching {
            container.clozeDeck().round(source, level, Random.Default, avoid = recent)
        }.getOrDefault(emptyList())
        for (item in items) recent[item.sentence.id] = item.blank
        room.startRound(items)
    }

    fun again() {
        viewModelScope.launch { startRound() }
    }

    fun onOptionPicked(index: Int) = room.onOptionPicked(index)
    fun onNext() = room.onNext()
    fun onSentenceTapped() = room.onSentenceTapped()

    /** Silence this room now, WITHOUT ending its round. A chip change keys a
     *  new room while this one stays retained until the screen leaves, so
     *  its voice would otherwise carry on over the next room's intro. But a
     *  rotation and a sticker detour leave the composition by the same door,
     *  and a round ENDED there could never be resumed — nothing restarts one
     *  for a retained room, so the learner would come back to an empty pane. */
    fun stop() = room.silence()

    override fun onCleared() = room.shutdown()
}

/**
 * Fill the gap (docs/fill-the-gap.md): a sentence with one word missing,
 * four words to choose from, and the Hebrew meaning underneath as the key.
 *
 * Built on the picture room's skeleton — chips for the source, a ViewModel
 * keyed on the choice, a tap game with no microphone — because it is the
 * same kind of room: recognition, not production. What it adds is READING:
 * the learner must read the line, read the Hebrew, and pick the word whose
 * meaning fits, which is the first exercise in the app a learner can finish
 * without saying a word of English.
 */
@Composable
fun ClozeScreen(container: AppContainer) {
    // Saved as its key string: a rotation restores the chosen chip, and the
    // parse is a plain function so a bad key falls back to the whole bank.
    var sourceKey by rememberSaveable { mutableStateOf(ClozeSource.All.sessionKey) }
    val source = remember(sourceKey) { ClozeSource.parse(sourceKey) }
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val level = profile.effectiveLevel
    val packs by produceState(initialValue = emptyList<PicturePack>(), container) {
        value = runCatching { container.picturePacks.packs() }.getOrDefault(emptyList())
    }
    val themes by produceState(initialValue = emptyList<PhraseTheme>(), container) {
        value = runCatching { container.phrasebank.themes() }.getOrDefault(emptyList())
    }
    // One rule hides every chip with nothing behind it at this Level: the
    // maths pack everywhere, the idioms theme everywhere, a thin pack early.
    val counts by produceState(initialValue = emptyMap<String, Int>(), container, level, packs, themes) {
        value = runCatching {
            val deck = container.clozeDeck()
            buildMap {
                for (p in packs) put(ClozeSource.Pack(p.id).sessionKey, deck.poolSize(ClozeSource.Pack(p.id), level))
                for (t in themes) put(ClozeSource.Theme(t.id).sessionKey, deck.poolSize(ClozeSource.Theme(t.id), level))
            }
        }.getOrDefault(emptyMap())
    }
    val viewModel: ClozeViewModel = viewModel(
        key = sourceKey,
        factory = viewModelFactory { initializer { ClozeViewModel(container, source) } },
    )
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = A11y.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The beak moves while the completed line is read — derived from
            // the room's own state, as the drill does.
            TukiParrot(
                speaking = (state as? ClozeState.Revealed)?.speaking == true,
                size = A11y.decorativeDp(comfortable = 56, minimum = 36),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloze_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                val progress = when (val s = state) {
                    is ClozeState.Asking -> s.asked + 1 to s.total
                    is ClozeState.Revealed -> s.asked + 1 to s.total
                    else -> null
                }
                if (progress != null) {
                    Text(
                        text = stringResource(R.string.vocab_progress, progress.first, progress.second),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // One scrolling line, not a wrapping cloud: forty topics wrapped
        // would push the sentence and the choices off a phone's screen.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = source == ClozeSource.All,
                onClick = { sourceKey = ClozeSource.All.sessionKey },
                label = { Text(stringResource(R.string.vocab_topic_any)) },
            )
            for (pack in packs) {
                val key = ClozeSource.Pack(pack.id).sessionKey
                if ((counts[key] ?: 0) <= 0) continue
                FilterChip(
                    selected = sourceKey == key,
                    onClick = { sourceKey = key },
                    label = { Text(if (hebrew) pack.title.he else pack.title.en) },
                )
            }
            for (theme in themes) {
                val key = ClozeSource.Theme(theme.id).sessionKey
                if ((counts[key] ?: 0) <= 0) continue
                FilterChip(
                    selected = sourceKey == key,
                    onClick = { sourceKey = key },
                    label = { Text((if (hebrew) theme.title?.he else theme.title?.en) ?: theme.id) },
                )
            }
        }
        EngineStatusLine()

        when (val s = state) {
            ClozeState.Idle -> Box(modifier = Modifier.weight(1f))

            is ClozeState.Asking -> ItemPane(
                container = container,
                item = s.item,
                level = level,
                wrongTaps = s.wrongTaps,
                revealed = false,
                outcome = null,
                onPick = viewModel::onOptionPicked,
                onSentenceTapped = {},
                onNext = {},
                modifier = Modifier.weight(1f),
            )

            is ClozeState.Revealed -> ItemPane(
                container = container,
                item = s.item,
                level = level,
                wrongTaps = s.wrongTaps,
                revealed = true,
                outcome = s.outcome,
                onPick = {},
                onSentenceTapped = viewModel::onSentenceTapped,
                onNext = viewModel::onNext,
                modifier = Modifier.weight(1f),
            )

            is ClozeState.Done -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(A11y.sectionGap, Alignment.CenterVertically),
            ) {
                if (s.total == 0) {
                    // Nothing to serve at this Level: the chips are the way out.
                    Text(
                        text = stringResource(R.string.cloze_empty),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.picture_done, s.firstTry, s.total),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = viewModel::again) {
                        Text(stringResource(R.string.vocab_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemPane(
    container: AppContainer,
    item: ClozeItem,
    level: Int,
    wrongTaps: Set<Int>,
    revealed: Boolean,
    outcome: ClozeOutcome?,
    onPick: (Int) -> Unit,
    onSentenceTapped: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sentence: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            ClozeSentence(
                container = container,
                item = item,
                level = level,
                wrongTaps = wrongTaps,
                revealed = revealed,
                onTap = onSentenceTapped,
            )
            if (!revealed && !A11y.cramped) {
                Text(
                    text = stringResource(R.string.cloze_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (outcome == ClozeOutcome.SHOWN) {
                Text(
                    text = stringResource(R.string.cloze_shown, item.options[item.answer]),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    val controls: @Composable () -> Unit = {
        ClozeOptions(item = item, wrongTaps = wrongTaps, revealed = revealed, onPick = onPick)
        if (revealed) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.picture_next))
            }
        }
    }
    if (A11y.wideViewport) {
        // Sideways, the line and the choices become columns: the interlinear
        // line is the thing that wants the width (docs/bilingual-gloss.md).
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(A11y.sectionGap),
        ) {
            sentence(Modifier.weight(1.4f).fillMaxHeight())
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                controls()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(A11y.sectionGap),
        ) {
            sentence(Modifier.weight(1f).fillMaxWidth())
            controls()
        }
    }
}

/**
 * The line with its gap, the pronunciation key under each word where the
 * Level wants one, and the Hebrew meaning underneath.
 *
 * The Hebrew is passed DIRECTLY, not through the translation switch: in
 * every other room it is a scaffold a parent may turn off, here it is the
 * question — the thing that makes the answer unique — so it is always shown.
 * The pronunciation row keeps its ordinary gate, and it is computed over the
 * ORIGINAL line and the gap column swapped in afterwards, so the columns and
 * the cues stay aligned and the answer's pronunciation never leaks.
 */
@Composable
private fun ClozeSentence(
    container: AppContainer,
    item: ClozeItem,
    level: Int,
    wrongTaps: Set<Int>,
    revealed: Boolean,
    onTap: () -> Unit,
) {
    val gloss by rememberGloss(container, item.sentence.en)
    val words = item.words
    val blankSpoken = stringResource(R.string.cloze_blank_spoken)
    val repeatLabel = stringResource(R.string.cloze_repeat)
    val shown = words.mapIndexed { i, w ->
        when {
            revealed -> if (gloss.size == words.size) gloss[i] else GlossWord(w, "")
            i == item.blank -> GlossWord(ClozeDeck.BLANK + w.takeLastWhile { it in TRAILING }, "")
            // The article that joined the gap: its column stays, empty, so
            // every index after it still lines up with its cue.
            i in item.span -> GlossWord("", "")
            gloss.size == words.size -> gloss[i]
            else -> GlossWord(w, "")
        }
    }
    // The Hebrew words that MEAN the gap light up through the align cues —
    // from the start at Levels 1–3, and after a first wrong tap above that,
    // so the cue is a hint rather than a giveaway.
    val hint = revealed || level <= 3 || wrongTaps.isNotEmpty()
    val lineStyle = when {
        A11y.hugeText -> MaterialTheme.typography.headlineMedium
        words.size > 8 -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.displaySmall
    }
    val description = words.mapIndexed { i, w ->
        when {
            revealed -> w
            i == item.blank -> blankSpoken
            i in item.span -> ""
            else -> w
        }
    }.filter { it.isNotEmpty() }.joinToString(" ") + ". " + item.sentence.he

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (revealed) {
                    Modifier.clickable(onClickLabel = repeatLabel) { onTap() }
                } else {
                    Modifier
                },
            ),
    ) {
        // A pack word's picture is the second key: shrunk under pressure,
        // never dropped, and named in Hebrew for a screen reader — which is
        // why the line's own description below must not swallow it.
        val pack = item.packWord
        if (item.kind == ClozeKind.PACK && pack != null) {
            PictureArtView(
                word = pack.en,
                emoji = PictureArt.emojiFor(pack.en),
                artSize = A11y.decorativeDp(comfortable = 96, minimum = 56),
                emojiSize = 56.sp,
                contentDescription = pack.he,
            )
        }
        Box(modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
            GlossedText(
                words = shown,
                style = lineStyle,
                glossStyle = MaterialTheme.typography.titleMedium,
                translation = item.sentence.he,
                highlightWordIndex = item.blank,
                translationCues = if (hint) item.sentence.align else null,
            )
        }
    }
}

/**
 * Four choices as full-width buttons. Each state shows through three
 * channels, never colour alone (PronunciationFeedback's rule): a wrong pick
 * is struck through, coloured for error, described, and disabled; the right
 * one, once revealed, carries a check, the teal secondary colour, and its
 * own description. Teal rather than the coral primary, which reads as red
 * beside an error.
 */
@Composable
private fun ClozeOptions(
    item: ClozeItem,
    wrongTaps: Set<Int>,
    revealed: Boolean,
    onPick: (Int) -> Unit,
) {
    val wrongLabel = stringResource(R.string.cloze_option_wrong)
    val rightLabel = stringResource(R.string.cloze_option_right)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.options.forEachIndexed { index, text ->
            val wrong = index in wrongTaps
            val right = revealed && index == item.answer
            OutlinedButton(
                onClick = { onPick(index) },
                enabled = !wrong && !revealed,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = A11y.tapTargetDp(comfortable = 72, minimum = 64))
                    .semantics {
                        stateDescription = when {
                            right -> rightLabel
                            wrong -> wrongLabel
                            else -> ""
                        }
                    },
            ) {
                EnglishContent {
                    Text(
                        text = if (right) "✓ $text" else text,
                        style = MaterialTheme.typography.titleLarge,
                        textDecoration = if (wrong) TextDecoration.LineThrough else null,
                        color = when {
                            wrong -> MaterialTheme.colorScheme.error
                            right -> MaterialTheme.colorScheme.secondary
                            else -> Color.Unspecified
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private const val TRAILING = ".,!?;:"

package org.sisam.langtutor.ui.picture

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.random.Random
import kotlinx.coroutines.launch
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import org.sisam.langtutor.content.PicturePack
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.R
import org.sisam.langtutor.content.Activity
import org.sisam.langtutor.tutor.picture.PictureCard
import org.sisam.langtutor.tutor.picture.PictureEvent
import org.sisam.langtutor.tutor.picture.PictureState
import org.sisam.langtutor.tutor.picture.PictureVocabOrchestrator
import org.sisam.langtutor.ui.common.A11y
import org.sisam.langtutor.ui.common.EngineStatusLine
import org.sisam.langtutor.ui.common.TukiParrot
import org.sisam.langtutor.speech.HebrewTransliteration.GlossWord
import org.sisam.langtutor.ui.common.GlossedText
import org.sisam.langtutor.ui.common.rememberGloss
import org.sisam.langtutor.ui.common.rememberTranslation
import org.sisam.langtutor.ui.reward.RewardKind

class PictureViewModel(
    private val container: AppContainer,
    /** Null teaches the curriculum's own vocabulary, as the room always has. */
    private val packId: String? = null,
) : ViewModel() {

    private val room = container.createPictureVocab(viewModelScope)
    val state = room.state

    init {
        viewModelScope.launch { room.startRound(freshCards()) }
        viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is PictureEvent.Correct ->
                        container.celebrate(if (event.firstTry) RewardKind.STAR else RewardKind.FLAKE)
                    PictureEvent.Wrong -> Unit
                }
            }
        }
    }

    /**
     * Every vocab word that HAS art becomes a candidate; the set to teach is
     * a small random handful (3-5 is what a learner holds — see the room
     * doc), fresh every round because recognition thrives on variety.
     */
    private suspend fun freshCards(): List<PictureCard> = candidates()
        .filter { PictureArt.hasArt(it.word) }
        .distinctBy { it.word }
        .shuffled(Random.Default)
        .take(SET_SIZE)

    /**
     * A chosen pack, or the curriculum's own vocabulary when none is chosen.
     *
     * A pack whose art has not landed yet simply contributes fewer cards; the
     * [PictureArt.hasArt] filter above is what keeps a wordless card off the
     * screen, and a card with a missing picture teaches nothing.
     */
    private suspend fun candidates(): List<PictureCard> {
        if (packId != null) {
            val pack = runCatching { container.picturePacks.packs() }.getOrDefault(emptyList())
                .firstOrNull { it.id == packId }
            if (pack != null) {
                return pack.words.map { PictureCard(it.en, it.he, PictureArt.emojiFor(it.en)) }
            }
        }
        return container.content.listUnits()
            .mapNotNull { container.content.loadUnit(it.id) }
            .flatMap { it.activities }
            .filterIsInstance<Activity.Vocab>()
            .map {
                PictureCard(
                    it.word,
                    it.translation.he.takeIf(String::isNotBlank),
                    PictureArt.emojiFor(it.word),
                )
            }
    }

    fun again() {
        viewModelScope.launch { room.startRound(freshCards()) }
    }

    fun onCardTapped(index: Int) = room.onCardTapped(index)
    fun onNext() = room.onNext()
    fun onAnswerPicked(index: Int) = room.onAnswerPicked(index)

    override fun onCleared() = room.shutdown()

    companion object {
        const val SET_SIZE = 4
    }
}

/**
 * The picture vocabulary room: see a picture, hear the word, then find the
 * picture Tuki asks for. Recognition before production — the one direction
 * the other rooms don't cover (docs/picture-vocabulary.md).
 */
@Composable
fun PictureScreen(container: AppContainer) {
    // Null is the curriculum's own vocabulary, which is what the room taught
    // before there were packs and what it still opens on.
    var packId by rememberSaveable { mutableStateOf<String?>(null) }
    val packs by produceState(initialValue = emptyList<PicturePack>(), container) {
        value = runCatching { container.picturePacks.packs() }.getOrDefault(emptyList())
    }
    val hebrew = LocalConfiguration.current.locales[0].language in setOf("he", "iw")
    // Keyed on the pack: choosing one starts a fresh session over its words
    // rather than reusing the round already in flight.
    val viewModel: PictureViewModel = viewModel(
        key = packId ?: "curriculum",
        factory = viewModelFactory { initializer { PictureViewModel(container, packId) } },
    )
    val state by viewModel.state.collectAsState()

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
            TukiParrot(speaking = false, size = A11y.decorativeDp(comfortable = 56, minimum = 36))
            Text(
                text = stringResource(R.string.picture_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        if (packs.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = packId == null,
                    onClick = { packId = null },
                    label = { Text(stringResource(R.string.picture_pack_any)) },
                )
                for (pack in packs) {
                    FilterChip(
                        selected = packId == pack.id,
                        onClick = { packId = pack.id },
                        label = { Text(if (hebrew) pack.title.he else pack.title.en) },
                    )
                }
            }
        }
        EngineStatusLine()

        when (val s = state) {
            PictureState.Idle -> Unit

            is PictureState.Teaching -> {
                val card = s.cards[s.index]
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Tapping the picture says the word again — free, always.
                    PictureArtView(
                        word = card.word,
                        emoji = card.emoji,
                        artSize = 132.dp,
                        emojiSize = 96.sp,
                        modifier = Modifier.clickable { viewModel.onCardTapped(s.index) },
                    )
                    // The same three-line treatment as every other room:
                    // word, pronunciation under it (level-gated), meaning
                    // below — this card's Hebrew is authored, so all three
                    // lines are trustworthy here.
                    val gloss by rememberGloss(container, card.word)
                    val meaning by rememberTranslation(container, card.hebrew)
                    if (gloss.isEmpty() && meaning == null) {
                        Text(text = card.word, style = MaterialTheme.typography.displaySmall)
                    } else {
                        GlossedText(
                            words = gloss.ifEmpty { listOf(GlossWord(card.word, "")) },
                            style = MaterialTheme.typography.displaySmall,
                            glossStyle = MaterialTheme.typography.titleMedium,
                            translation = meaning,
                        )
                    }
                }
                Button(
                    onClick = viewModel::onNext,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.picture_next))
                }
            }

            is PictureState.Asking -> {
                Text(
                    text = stringResource(
                        R.string.picture_where_is,
                        s.cards[s.targetIndex].word,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    s.cards.forEachIndexed { index, card ->
                        val wrong = index in s.wrongTaps
                        Card(
                            modifier = Modifier
                                .size(A11y.tapTargetDp(comfortable = 112, minimum = 88))
                                .alpha(if (wrong) 0.35f else 1f)
                                .clickable(enabled = !wrong) { viewModel.onAnswerPicked(index) },
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PictureArtView(
                                    word = card.word,
                                    emoji = card.emoji,
                                    artSize = 76.dp,
                                    emojiSize = 56.sp,
                                )
                            }
                        }
                    }
                }
            }

            is PictureState.Done -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(A11y.sectionGap, Alignment.CenterVertically),
                ) {
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

/**
 * One card's picture: the curated vector icon when the word has one, the
 * emoji fallback otherwise ([PictureArt]'s two tiers). contentDescription is
 * null on purpose — the word is always printed or spoken right beside the
 * picture, and reading it twice teaches TalkBack users to stop listening.
 */
@Composable
private fun PictureArtView(
    word: String,
    emoji: String?,
    artSize: Dp,
    emojiSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val drawable = PictureArt.drawableFor(word)
    if (drawable != null) {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            modifier = modifier.size(artSize),
        )
    } else if (emoji != null) {
        Text(text = emoji, fontSize = emojiSize, modifier = modifier)
    }
}

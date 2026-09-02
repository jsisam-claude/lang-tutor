package org.sisam.langtutor.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.compose.ui.platform.LocalView
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.tutor.LevelConfig
import org.sisam.langtutor.ui.chat.ChatScreen
import org.sisam.langtutor.ui.conversation.ConversationScreen
import org.sisam.langtutor.ui.home.HomeScreen
import org.sisam.langtutor.ui.lesson.LessonScreen
import org.sisam.langtutor.ui.parent.ParentZoneScreen
import org.sisam.langtutor.ui.reward.RewardOverlay
import org.sisam.langtutor.ui.reward.StickerMilestones
import org.sisam.langtutor.ui.sticker.StickerRoom
import org.sisam.langtutor.ui.picture.PictureScreen
import org.sisam.langtutor.ui.vocab.VocabScreen

object Routes {
    const val HOME = "home"
    const val PARENT = "parent"
    const val CHAT = "chat"
    const val STICKER = "sticker"
    const val VOCAB = "vocab"
    const val PICTURES = "pictures"

    // Unit-scoped destinations: the tapped unit travels in the route so every
    // screen teaches THAT unit (previously all roads led to unit-001).
    const val LESSON = "lesson/{unitId}"
    const val CONVERSATION = "conversation/{unitId}"

    fun lesson(unitId: String) = "lesson/$unitId"
    fun conversation(unitId: String) = "conversation/$unitId"

    const val DEFAULT_UNIT = "unit-001"

    /** The places a child is actually working — the only ones the sticker
     *  room may interrupt, and the only ones it returns to. */
    fun isLearningRoom(route: String?) =
        route == LESSON || route == CONVERSATION || route == VOCAB || route == PICTURES
}

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenLesson = { unitId -> navController.navigate(Routes.lesson(unitId)) },
                    onOpenConversation = { unitId -> navController.navigate(Routes.conversation(unitId)) },
                    onOpenParent = { navController.navigate(Routes.PARENT) },
                    onOpenChat = { navController.navigate(Routes.CHAT) },
                    onOpenVocab = { navController.navigate(Routes.VOCAB) },
                    onOpenPictures = { navController.navigate(Routes.PICTURES) },
                )
            }
            composable(
                Routes.LESSON,
                arguments = listOf(navArgument("unitId") { type = NavType.StringType }),
            ) { entry ->
                LessonScreen(container, entry.arguments?.getString("unitId") ?: Routes.DEFAULT_UNIT)
            }
            composable(
                Routes.CONVERSATION,
                arguments = listOf(navArgument("unitId") { type = NavType.StringType }),
            ) { entry ->
                ConversationScreen(container, entry.arguments?.getString("unitId") ?: Routes.DEFAULT_UNIT)
            }
            composable(Routes.VOCAB) { VocabScreen(container) }
            composable(Routes.PICTURES) { PictureScreen(container) }
            composable(Routes.PARENT) { ParentZoneScreen(container) }
            composable(Routes.CHAT) { ChatScreen(container) }
            composable(Routes.STICKER) {
                // popBackStack, not navigate: the room is a detour, and the
                // learner lands back in the exact lesson they left, mid-session.
                StickerRoom(container) { navController.popBackStack() }
            }
        }

        // Above the graph on purpose: a burst fired as a turn ends keeps flying
        // over whatever screen comes next, and the overlay takes no input.
        RewardOverlay(container.rewards)
    }

    StickerMilestone(container, navController)
    KeepScreenOn(navController)
}

/**
 * The screen stays on while a room is open. A tablet on a stand — or a
 * phone propped against a cup — dims and locks a minute into a drill
 * otherwise, mid-sentence, with the mic listening to nobody. Only the rooms
 * where a child is talking to Tuki; the Parent Zone and the sticker book
 * follow the system timeout like any screen. The daily-minutes limit is
 * what bounds how long this can hold the panel awake.
 */
@Composable
private fun KeepScreenOn(navController: NavHostController) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val view = LocalView.current
    DisposableEffect(view, route) {
        view.keepScreenOn = Routes.isLearningRoom(route) || route == Routes.CHAT
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Sends an early-level learner to the sticker room when they have earned
 * one — at Level 1 the celebration IS the reward loop; above that the
 * sticker lands quietly in the book.
 *
 * The bookkeeping is deliberately derived rather than stored: stickers earned
 * is XP over a threshold, stickers owned is the length of the collection, and
 * a child is owed one whenever the first exceeds the second. Nothing to keep
 * in sync, and it survives a crash mid-celebration — the room simply opens
 * again next time.
 *
 * Two guards. It only interrupts a learning room, never the Parent Zone or a
 * chat. And a room already offered for the same state is not re-offered, so a
 * child who backs out is not trapped in a loop back into it — while a child
 * who earned three at once still gets three trips. [StickerMilestones.owed]
 * owns that distinction; this composable only decides WHERE it may interrupt.
 */
@Composable
private fun StickerMilestone(container: AppContainer, navController: NavHostController) {
    val profile by container.profile.profile.collectAsState(initial = LearnerProfile.EMPTY)
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // The pair the decline guard needs: what the collection looked like when
    // the room was last opened, and which milestone that was.
    var offeredAtOwned by rememberSaveable { mutableIntStateOf(StickerMilestones.NEVER_OFFERED) }
    var offeredAtEarned by rememberSaveable { mutableIntStateOf(0) }

    // The ceremony learner by EITHER signal: the profile's level, or the
    // level of the unit actually open. Someone who never chose a level still
    // gets the right behaviour inside a Level 1 unit.
    val unitId = entry?.arguments?.getString("unitId")
    val unitLevel by produceState<Int?>(initialValue = null, unitId) {
        value = unitId?.let { container.content.loadUnit(it).level }
    }
    val ceremony = LevelConfig.of(profile.effectiveLevel).stickerCeremony ||
        (unitLevel ?: Int.MAX_VALUE) <= LevelConfig.EARLY_UNIT_LEVEL

    val owed = StickerMilestones.owed(
        xp = profile.xp,
        owned = profile.stickers.size,
        lastOfferedOwned = offeredAtOwned,
        lastOfferedEarned = offeredAtEarned,
    )

    LaunchedEffect(owed, ceremony, route) {
        if (owed && ceremony && Routes.isLearningRoom(route)) {
            offeredAtOwned = profile.stickers.size
            offeredAtEarned = StickerMilestones.earned(profile.xp)
            navController.navigate(Routes.STICKER)
        }
    }
}

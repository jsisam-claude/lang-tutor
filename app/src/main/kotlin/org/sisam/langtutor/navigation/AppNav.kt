package org.sisam.langtutor.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.content.AgeBand
import org.sisam.langtutor.profile.LearnerProfile
import org.sisam.langtutor.profile.LearnerTrack
import org.sisam.langtutor.ui.chat.ChatScreen
import org.sisam.langtutor.ui.conversation.ConversationScreen
import org.sisam.langtutor.ui.home.HomeScreen
import org.sisam.langtutor.ui.lesson.LessonScreen
import org.sisam.langtutor.ui.parent.ParentZoneScreen
import org.sisam.langtutor.ui.reward.RewardOverlay
import org.sisam.langtutor.ui.reward.StickerMilestones
import org.sisam.langtutor.ui.sticker.StickerRoom
import org.sisam.langtutor.ui.vocab.VocabScreen

object Routes {
    const val HOME = "home"
    const val PARENT = "parent"
    const val CHAT = "chat"
    const val STICKER = "sticker"
    const val VOCAB = "vocab"

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
        route == LESSON || route == CONVERSATION || route == VOCAB
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
}

/**
 * Sends a young learner to the sticker room when they have earned one.
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

    // A young learner by EITHER signal: the profile's track, or the age band of
    // the unit actually open. A parent who never set the track still gets the
    // right behaviour inside a 4-6 unit.
    val unitId = entry?.arguments?.getString("unitId")
    val ageBand by produceState<AgeBand?>(initialValue = null, unitId) {
        value = unitId?.let { container.content.loadUnit(it).ageBand }
    }
    val young = profile.track == LearnerTrack.PRE_READER || ageBand == AgeBand.AGES_4_6

    val owed = StickerMilestones.owed(
        xp = profile.xp,
        owned = profile.stickers.size,
        lastOfferedOwned = offeredAtOwned,
        lastOfferedEarned = offeredAtEarned,
    )

    LaunchedEffect(owed, young, route) {
        if (owed && young && Routes.isLearningRoom(route)) {
            offeredAtOwned = profile.stickers.size
            offeredAtEarned = StickerMilestones.earned(profile.xp)
            navController.navigate(Routes.STICKER)
        }
    }
}

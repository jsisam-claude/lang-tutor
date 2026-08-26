package org.sisam.langtutor.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.ui.chat.ChatScreen
import org.sisam.langtutor.ui.conversation.ConversationScreen
import org.sisam.langtutor.ui.home.HomeScreen
import org.sisam.langtutor.ui.lesson.LessonScreen
import org.sisam.langtutor.ui.parent.ParentZoneScreen

object Routes {
    const val HOME = "home"
    const val PARENT = "parent"
    const val CHAT = "chat"

    // Unit-scoped destinations: the tapped unit travels in the route so every
    // screen teaches THAT unit (previously all roads led to unit-001).
    const val LESSON = "lesson/{unitId}"
    const val CONVERSATION = "conversation/{unitId}"

    fun lesson(unitId: String) = "lesson/$unitId"
    fun conversation(unitId: String) = "conversation/$unitId"

    const val DEFAULT_UNIT = "unit-001"
}

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenLesson = { unitId -> navController.navigate(Routes.lesson(unitId)) },
                onOpenConversation = { unitId -> navController.navigate(Routes.conversation(unitId)) },
                onOpenParent = { navController.navigate(Routes.PARENT) },
                onOpenChat = { navController.navigate(Routes.CHAT) },
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
        composable(Routes.PARENT) { ParentZoneScreen(container) }
        composable(Routes.CHAT) { ChatScreen(container) }
    }
}

package org.sisam.langtutor.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.sisam.langtutor.AppContainer
import org.sisam.langtutor.ui.conversation.ConversationScreen
import org.sisam.langtutor.ui.home.HomeScreen
import org.sisam.langtutor.ui.lesson.LessonScreen
import org.sisam.langtutor.ui.parent.ParentZoneScreen

object Routes {
    const val HOME = "home"
    const val LESSON = "lesson"
    const val CONVERSATION = "conversation"
    const val PARENT = "parent"
}

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenLesson = { navController.navigate(Routes.LESSON) },
                onOpenConversation = { navController.navigate(Routes.CONVERSATION) },
                onOpenParent = { navController.navigate(Routes.PARENT) },
            )
        }
        composable(Routes.LESSON) { LessonScreen(container) }
        composable(Routes.CONVERSATION) { ConversationScreen(container) }
        composable(Routes.PARENT) { ParentZoneScreen(container) }
    }
}

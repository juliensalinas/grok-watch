package com.jsalinas.grokwatch.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

private const val ROUTE_CHAT = "chat/{id}"
private const val ROUTE_HISTORY = "history"

@Composable
fun GrokWatchRoot() {
    MaterialTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = "chat/-1") {
                composable(ROUTE_CHAT) { entry ->
                    val id = entry.arguments?.getString("id")?.toLongOrNull() ?: -1L
                    ChatScreen(
                        conversationId = id,
                        onOpenHistory = { nav.navigate(ROUTE_HISTORY) },
                        onNewChat = { nav.openChat(-1L) },
                    )
                }
                composable(ROUTE_HISTORY) {
                    HistoryScreen(
                        onOpen = { nav.openChat(it) },
                        onNewChat = { nav.openChat(-1L) },
                    )
                }
            }
        }
    }
}

/** Opens a conversation as the new root, so swiping back exits the app (and stops the mic). */
private fun NavHostController.openChat(id: Long) {
    navigate("chat/$id") {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = false
    }
}

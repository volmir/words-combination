package com.example.sostavslova.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.sostavslova.ui.game.GameRoute
import com.example.sostavslova.ui.levels.LevelsRoute
import com.example.sostavslova.ui.screens.*

fun NavHostController.returnToLevels(message: Int = 0) {
    if (!popBackStack(Destination.Levels.route, inclusive = false)) {
        navigate(Destination.Levels.route) {
            popUpTo(graph.id) { inclusive = false }
            launchSingleTop = true
        }
    }
    currentBackStackEntry?.savedStateHandle?.set("levels.notice", message)
}

@Composable
fun WordsNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Destination.Levels.route, modifier = modifier) {
        composable(Destination.Levels.route) { entry ->
            val notice by entry.savedStateHandle.getStateFlow("levels.notice", 0).collectAsStateWithLifecycle()
            LevelsRoute(notice) { index ->
                entry.savedStateHandle["levels.notice"] = 0
                navController.navigate(Destination.level(index)) { launchSingleTop = true }
            }
        }
        composable(Destination.Game.route,
            arguments = listOf(navArgument("index") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "sostavslova://level/{index}" }),
        ) {
            GameRoute { message -> navController.returnToLevels(message) }
        }
        composable(Destination.WordSearch.route) { WordSearchScreen() }
        composable(Destination.WordMeaning.route) { WordMeaningScreen() }
        composable(Destination.Rules.route) { RulesScreen() }
        composable(Destination.About.route) { AboutScreen() }
    }
}

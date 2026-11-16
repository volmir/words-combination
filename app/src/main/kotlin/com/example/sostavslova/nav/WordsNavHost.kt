package com.example.sostavslova.nav

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.sostavslova.ui.game.GameRoute
import com.example.sostavslova.ui.game.GameViewModel
import com.example.sostavslova.ui.levels.LevelsRoute
import com.example.sostavslova.ui.screens.*

fun NavHostController.returnToLevels(message: Int = 0, saveLevelState: Boolean = false) {
    if (saveLevelState) {
        val gameEntry = currentBackStackEntry
        val index = gameEntry?.arguments?.getString("index")
        val source = gameEntry?.savedStateHandle?.get<String>(GameViewModel.SOURCE)
        val levelsEntry = previousBackStackEntry?.takeIf { it.destination.route == Destination.Levels.route }
        if (index != null && source != null && levelsEntry != null) {
            levelsEntry.savedStateHandle["level.draft.$index"] = Bundle().apply {
                putString(GameViewModel.SOURCE, source)
                putString(GameViewModel.INPUT, gameEntry.savedStateHandle.get<String>(GameViewModel.INPUT).orEmpty())
            }
        }
    }
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
        ) { entry ->
            val levelsEntry = remember(entry) {
                navController.previousBackStackEntry?.takeIf { it.destination.route == Destination.Levels.route }
            }
            if (!entry.savedStateHandle.contains(GameViewModel.INPUT)) {
                val index = entry.arguments?.getString("index")
                val draft = levelsEntry?.savedStateHandle?.get<Bundle>("level.draft.$index")
                if (draft != null) {
                    entry.savedStateHandle[GameViewModel.INPUT] = draft.getString(GameViewModel.INPUT).orEmpty()
                    entry.savedStateHandle[GameViewModel.SOURCE] = draft.getString(GameViewModel.SOURCE)
                }
            }
            GameRoute(entry.id) { message, saveLevelState -> navController.returnToLevels(message, saveLevelState) }
        }
        composable(Destination.Time.route) { com.example.sostavslova.ui.time.TimeRoute() }
        composable(Destination.Rules.route) { RulesScreen() }
        composable(Destination.About.route) { AboutScreen() }
    }
}

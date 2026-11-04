package com.example.sostavslova.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.sostavslova.ui.screens.AboutScreen
import com.example.sostavslova.ui.screens.ContactsScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sostavslova.ui.game.GameRoute
import com.example.sostavslova.ui.home.HomeRoute
import com.example.sostavslova.ui.levels.LevelsRoute
import com.example.sostavslova.ui.screens.RulesScreen
import com.example.sostavslova.ui.screens.WordMeaningScreen
import com.example.sostavslova.ui.screens.WordSearchScreen

@Composable
fun WordsNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController, startDestination = Destination.Home.route, modifier = modifier) {
        composable(Destination.Home.route) { entry ->
            val notice by entry.savedStateHandle.getStateFlow("home.notice", 0).collectAsStateWithLifecycle()
            HomeRoute(notice, { entry.savedStateHandle["home.notice"] = 0 }) {
                navController.navigate(Destination.Game.route) { launchSingleTop = true }
            }
        }
        composable(Destination.Game.route) {
            GameRoute { message ->
                navController.getBackStackEntry(Destination.Home.route).savedStateHandle["home.notice"] = message
                navController.navigate(Destination.Home.route) {
                    popUpTo(Destination.Home.route)
                    launchSingleTop = true
                }
            }
        }
        composable(Destination.Levels.route) {
            LevelsRoute { navController.navigate(Destination.Game.route) { launchSingleTop = true } }
        }
        composable(Destination.WordSearch.route) { WordSearchScreen() }
        composable(Destination.WordMeaning.route) { WordMeaningScreen() }
        composable(Destination.Rules.route) { RulesScreen() }
        composable(Destination.Contacts.route) { ContactsScreen() }
        composable(Destination.About.route) { AboutScreen() }
    }
}

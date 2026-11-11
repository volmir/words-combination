package com.example.sostavslova.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.nav.returnToLevels
import com.example.sostavslova.nav.WordsNavHost
import com.example.sostavslova.ui.components.WordsDrawer
import com.example.sostavslova.ui.components.WordsTopBar
import kotlinx.coroutines.launch

@Composable
fun WordsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val inLevel = backStackEntry?.destination?.route == Destination.Game.route

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !inLevel,
        drawerContent = {
            WordsDrawer(
                backStackEntry?.destination?.route,
            ) { destination ->
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                // Восстановленный стек «Прохождение → Игра» может содержать дочерний экран.
                // Выбор пункта меню должен показывать именно выбранный раздел.
                navController.popBackStack(destination.route, inclusive = false)
                scope.launch { drawerState.close() }
            }
        },
        content = {
            Scaffold(
                topBar = { WordsTopBar(onBack = if (inLevel) ({ navController.returnToLevels() }) else null) {
                    scope.launch { drawerState.open() }
                } },
            ) { innerPadding ->
                WordsNavHost(
                    navController,
                    Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                )
            }
        },
    )
}

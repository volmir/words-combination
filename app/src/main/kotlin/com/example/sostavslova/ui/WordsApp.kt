package com.example.sostavslova.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.sostavslova.R
import com.example.sostavslova.di.appContainer
import kotlinx.coroutines.delay
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
import androidx.navigation.NavController
import com.example.sostavslova.nav.Destination
import com.example.sostavslova.nav.returnToLevels
import com.example.sostavslova.nav.WordsNavHost
import com.example.sostavslova.ui.components.WordsDrawer
import com.example.sostavslova.ui.components.WordsTopBar
import kotlinx.coroutines.launch
import com.example.sostavslova.time.LocalPlayTimeTracker
import com.example.sostavslova.time.PlayTimeWindowFocus
import com.example.sostavslova.time.observePlayTimeLifecycle
import com.example.sostavslova.time.playTimeUserActivity

@Composable
fun WordsApp() {
    val context = LocalContext.current
    val container = appContainer()
    val exit: ExitViewModel = viewModel(factory = viewModelFactory {
        initializer { ExitViewModel(createSavedStateHandle(), container::awaitSaved) }
    })
    val exitState by exit.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(exitState.finish, lifecycleOwner, context) {
        if (exitState.finish) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            context.findActivity()?.finishAffinity()
        }
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tracker = container.playTimeTracker
    val timeFailure by tracker.writeFailure.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner, tracker) {
        val dispose = observePlayTimeLifecycle(lifecycleOwner.lifecycle, tracker::setActivityResumed)
        onDispose { dispose() }
    }
    DisposableEffect(navController, tracker) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, arguments ->
            tracker.setRoute(controller.currentBackStackEntry?.id,
                if (destination.route == Destination.Game.route) arguments?.getString("index")?.toIntOrNull() else null)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
            tracker.setRoute(null, null)
        }
    }
    LaunchedEffect(drawerState, tracker) {
        snapshotFlow { drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed }
            .collect(tracker::setDrawerOpen)
    }

    val inLevel = backStackEntry?.destination?.route == Destination.Game.route

    CompositionLocalProvider(LocalPlayTimeTracker provides tracker) {
        PlayTimeWindowFocus()
        ModalNavigationDrawer(
            modifier = Modifier.playTimeUserActivity(tracker),
            drawerState = drawerState,
            gesturesEnabled = !inLevel,
            drawerContent = {
                WordsDrawer(
                    backStackEntry?.destination?.route,
                    onExit = {
                        exit.open()
                        scope.launch { drawerState.close() }
                    },
                ) { destination ->
                    if (inLevel) navController.returnToLevels()
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
                    snackbarHost = {
                        if (exitState.backDeadline != null) Snackbar {
                            Text(stringResource(R.string.exit_press_back_again))
                        } else if (timeFailure != null) Snackbar(Modifier.testTag("game_time_save_error")) {
                            Text(stringResource(R.string.play_time_save_error))
                        }
                    },
                    topBar = { WordsTopBar(onBack = if (inLevel) ({ navController.returnToLevels() }) else null) {
                        tracker.setDrawerOpen(true)
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

        // После NavHost: перекрываем его BackHandler только на карте и поверх drawer.
        BackHandler(enabled = backStackEntry?.destination?.route == Destination.Levels.route &&
            !drawerState.isOpen && !exitState.dialog) { exit.backOnMap() }
        BackHandler(enabled = drawerState.isOpen && !exitState.dialog) {
            exit.resetBack()
            scope.launch { drawerState.close() }
        }
        LaunchedEffect(backStackEntry?.destination?.route, drawerState.isOpen) { exit.resetBack() }
        LaunchedEffect(exitState.backDeadline) {
            exitState.backDeadline?.let {
                delay((it - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                exit.resetBack()
            }
        }
        ExitDialog(exitState, exit::confirm) {
            tracker.setExiting(false)
            exit.cancel()
        }
    }
}

@Composable
internal fun ExitDialog(state: ExitUiState, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (!state.dialog) return
    AlertDialog(
        modifier = Modifier.playTimeUserActivity(LocalPlayTimeTracker.current),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.exit_title)) },
        text = { Text(stringResource(when {
            state.error -> R.string.exit_save_error
            state.saved -> R.string.exit_saved
            else -> R.string.exit_saving
        })) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.busy,
                modifier = Modifier.testTag("exit_dialog_confirm")) { Text(stringResource(R.string.exit_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("exit_dialog_cancel")) {
                Text(stringResource(R.string.exit_cancel))
            }
        },
    )
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
    while (visited.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

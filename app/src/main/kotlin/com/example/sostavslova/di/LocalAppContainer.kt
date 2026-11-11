package com.example.sostavslova.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.example.sostavslova.WordsApplication

/** Позволяет всему тестовому дереву использовать один изолированный контейнер. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer?> { null }

@Composable
fun appContainer(): AppContainer = LocalAppContainer.current
    ?: (LocalContext.current.applicationContext as WordsApplication).container

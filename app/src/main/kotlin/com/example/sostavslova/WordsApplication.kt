package com.example.sostavslova

import android.app.Application
import com.example.sostavslova.di.AppContainer
import kotlinx.coroutines.launch

class WordsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.applicationScope.launch { container.wordRepository.ensureLoaded() }
        container.applicationScope.launch { container.definitionsRepository.isAvailable() }
    }
}

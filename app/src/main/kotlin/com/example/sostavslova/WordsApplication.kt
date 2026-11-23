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
        container.discardLegacyTimeFiles()
        container.applicationScope.launch { container.wordRepository.ensureLoaded() }
    }
}

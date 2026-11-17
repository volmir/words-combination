package com.example.sostavslova.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.runtime.CompositionLocalProvider
import com.example.sostavslova.MainActivity
import com.example.sostavslova.di.AppContainer
import com.example.sostavslova.di.LocalAppContainer
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.rules.ExternalResource

/** Внешнее правило: подменяет контент до первого кадра, в том числе после recreate(). */
class IsolatedGameStorageRule : ExternalResource() {
    lateinit var container: AppContainer
        private set
    private lateinit var directory: File
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            activity.setContent {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    WordsTheme { WordsApp() }
                }
            }
        }
    }

    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        directory = File(context.cacheDir, "navigation-${UUID.randomUUID()}").apply { mkdirs() }
        // AppContainer использует applicationContext.filesDir для обоих DataStore.
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        container = AppContainer(isolated)
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        }
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
        runTest {
            try {
                container.awaitSaved()
            } finally {
                container.applicationScope.cancel()
                container.applicationScope.coroutineContext.job.join()
                directory.deleteRecursively()
            }
        }
    }
}

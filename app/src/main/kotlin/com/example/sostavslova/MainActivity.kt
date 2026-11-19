package com.example.sostavslova

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.example.sostavslova.ui.WordsApp
import com.example.sostavslova.ui.theme.WordsTheme
import com.example.sostavslova.ui.theme.wordsPalette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdge(resources.configuration)
        setContent {
            WordsTheme {
                WordsApp()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyEdgeToEdge(newConfig)
    }

    /** Панели и их иконки соответствуют активной теме; insets задаёт enableEdgeToEdge. */
    private fun applyEdgeToEdge(configuration: Configuration) {
        val isDarkTheme = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val navbarScrim = wordsPalette(isDarkTheme).navbar.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (isDarkTheme) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, navbarScrim)
            },
        )
    }
}

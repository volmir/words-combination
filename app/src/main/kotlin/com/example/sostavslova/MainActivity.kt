package com.example.sostavslova

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.example.sostavslova.ui.WordsApp
import com.example.sostavslova.ui.theme.WordsColors
import com.example.sostavslova.ui.theme.WordsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                WordsColors.navbar.toArgb(),
            ),
        )
        setContent {
            WordsTheme {
                WordsApp()
            }
        }
    }
}

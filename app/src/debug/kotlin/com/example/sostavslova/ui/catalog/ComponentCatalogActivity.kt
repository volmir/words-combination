package com.example.sostavslova.ui.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Остаток зарегистрированной debug Activity до удаления вместе с записью в PACK-01. */
class ComponentCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

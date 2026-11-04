package com.example.sostavslova.ui.catalog

import android.content.Intent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.sostavslova.R
import com.example.sostavslova.ui.components.WordsDrawerItem

@Composable
internal fun BuildVariantDrawerEntries(onSelected: () -> Unit) {
    val context = LocalContext.current
    HorizontalDivider()
    WordsDrawerItem(stringResource(R.string.catalog_title)) {
        onSelected()
        context.startActivity(Intent(context, ComponentCatalogActivity::class.java))
    }
}

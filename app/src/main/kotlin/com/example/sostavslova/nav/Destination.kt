package com.example.sostavslova.nav

import androidx.annotation.StringRes
import com.example.sostavslova.R

enum class Destination(val route: String, @param:StringRes val label: Int) {
    Game("level/{index}", R.string.nav_levels),
    Levels("levels", R.string.nav_levels),
    WordSearch("word-search", R.string.nav_word_search),
    WordMeaning("word-meaning", R.string.nav_word_meaning),
    Rules("rules", R.string.nav_rules),
    About("about", R.string.nav_about);

    companion object {
        fun level(index: Int): String = "level/$index"
    }
}

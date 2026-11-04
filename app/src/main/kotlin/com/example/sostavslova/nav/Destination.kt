package com.example.sostavslova.nav

import androidx.annotation.StringRes
import com.example.sostavslova.R

enum class Destination(val route: String, @param:StringRes val label: Int) {
    Home("home", R.string.nav_home),
    Game("game", R.string.nav_game),
    Levels("levels", R.string.nav_levels),
    WordSearch("word-search", R.string.nav_word_search),
    WordMeaning("word-meaning", R.string.nav_word_meaning),
    Rules("rules", R.string.nav_rules),
    Contacts("contacts", R.string.nav_contacts),
    About("about", R.string.nav_about),
}

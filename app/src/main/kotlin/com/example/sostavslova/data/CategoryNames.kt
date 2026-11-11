package com.example.sostavslova.data

import androidx.annotation.StringRes
import com.example.sostavslova.R

/** Единый справочник русских названий для заданий и лестницы подсказок. */
object CategoryNames {
    @StringRes
    fun resource(key: String): Int? = when (key) {
    "animals" -> R.string.game_category_animals
    "birds" -> R.string.game_category_birds
    "fish" -> R.string.game_category_fish
    "insects" -> R.string.game_category_insects
    "plants" -> R.string.game_category_plants
    "fruits" -> R.string.game_category_fruits
    "vegetables" -> R.string.game_category_vegetables
    "food" -> R.string.game_category_food
    "drinks" -> R.string.game_category_drinks
    "cooking" -> R.string.game_category_cooking
    "home" -> R.string.game_category_home
    "furniture" -> R.string.game_category_furniture
    "utensils" -> R.string.game_category_utensils
    "clothing" -> R.string.game_category_clothing
    "tools" -> R.string.game_category_tools
    "technology" -> R.string.game_category_technology
    "transport" -> R.string.game_category_transport
    "buildings" -> R.string.game_category_buildings
    "places" -> R.string.game_category_places
    "nature" -> R.string.game_category_nature
    "weather" -> R.string.game_category_weather
    "materials" -> R.string.game_category_materials
    "body" -> R.string.game_category_body
    "health" -> R.string.game_category_health
    "people" -> R.string.game_category_people
    "family" -> R.string.game_category_family
    "professions" -> R.string.game_category_professions
    "society" -> R.string.game_category_society
    "education" -> R.string.game_category_education
    "science" -> R.string.game_category_science
    "language" -> R.string.game_category_language
    "literature" -> R.string.game_category_literature
    "art" -> R.string.game_category_art
    "music" -> R.string.game_category_music
    "sport" -> R.string.game_category_sport
    "work" -> R.string.game_category_work
    "time" -> R.string.game_category_time
    "feelings" -> R.string.game_category_feelings
    "actions" -> R.string.game_category_actions
    "other" -> R.string.game_category_other
    else -> null
}

}

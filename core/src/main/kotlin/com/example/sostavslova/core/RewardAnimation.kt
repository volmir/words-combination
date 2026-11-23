package com.example.sostavslova.core

/** Обычные ответы и ошибки не являются поводом для анимации награды. */
fun rewardAnimation(stars: Int, openedNext: Boolean, achievements: Collection<LevelAchievement>): Boolean =
    stars > 0 || openedNext || achievements.isNotEmpty()

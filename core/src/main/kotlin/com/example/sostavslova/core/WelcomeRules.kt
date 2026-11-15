package com.example.sostavslova.core

/** Однократное приветствие хранится отдельно от прогресса кампании. */
enum class WelcomeState {
    /** Решения ещё нет: ключ отсутствует или повреждён. */
    UNKNOWN,

    /** Новая установка подтверждена; приветствие ждёт фактического показа. */
    SHOW,

    /** Приветствие уже предъявлено и повторно не показывается. */
    DONE,
}

/** Насколько надёжно пакетный менеджер подтверждает первую установку. */
enum class InstallEvidence {
    FRESH_INSTALL,
    EXISTING_INSTALL,
    UNKNOWN,
}

object WelcomeRules {
    /**
     * Равенство времён установки и обновления — офлайн-признак первой установки.
     * Нули, обратный порядок и любые неизвестные значения не считаются новым
     * запуском: возвращается UNKNOWN (fail-closed).
     */
    fun installEvidence(firstInstallTime: Long, lastUpdateTime: Long): InstallEvidence = when {
        firstInstallTime <= 0L || lastUpdateTime <= 0L -> InstallEvidence.UNKNOWN
        firstInstallTime == lastUpdateTime -> InstallEvidence.FRESH_INSTALL
        lastUpdateTime > firstInstallTime -> InstallEvidence.EXISTING_INSTALL
        else -> InstallEvidence.UNKNOWN
    }

    /**
     * Отсутствие ключа приветствия само по себе не доказывает новую установку:
     * SHOW выдаётся только при подтверждённой первой установке и отсутствии
     * локального состояния, существовавшего до запуска. Любая неопределённость
     * остаётся UNKNOWN, то есть приветствие не показывается (fail-closed).
     */
    fun resolve(
        stored: WelcomeState,
        hadLocalStateAtStartup: Boolean,
        evidence: InstallEvidence,
    ): WelcomeState = when (stored) {
        WelcomeState.SHOW, WelcomeState.DONE -> stored
        WelcomeState.UNKNOWN ->
            if (evidence == InstallEvidence.FRESH_INSTALL && !hadLocalStateAtStartup) {
                WelcomeState.SHOW
            } else {
                WelcomeState.UNKNOWN
            }
    }
}

package com.example.sostavslova.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.core.okio.OkioStorage
import com.example.sostavslova.core.*
import com.example.sostavslova.data.CampaignJson
import com.example.sostavslova.data.LevelsRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val stageNames = listOf("Разминка", "Первая буква", "Длинные слова", "Загадки", "Окончания", "Эксперт")
    private val stageBounds = listOf(1 to 3, 4 to 5, 6 to 8, 9 to 10, 11 to 13, 14 to 15)

    private fun row(
        number: Int,
        word: String,
        stage: Int,
        stars: Int = 0,
        maxStars: Int = 3,
        requiredStars: Int = 2,
        found: Int = 0,
        total: Int = 325,
        hints: Int = 0,
        byLength: List<LengthStat> = listOf(LengthStat(3, 0, 100), LengthStat(4, 0, 100),
            LengthStat(5, 0, total - 200)),
    ) = LevelStatsRow(number, word, stageNames[stage - 1], stars, maxStars, requiredStars,
        found, total, hints, byLength,
        achievements = setOf(LevelAchievement.EXPERT),
        achievementsAvailable = setOf(LevelAchievement.NO_HINTS, LevelAchievement.EXPERT))

    private val rows = listOf(
        row(1, "чемпионство", 1, maxStars = 1, requiredStars = 1),
        row(2, "вегетарианец", 1, stars = 1, maxStars = 1, requiredStars = 1, found = 80),
        row(3, "аккумулятор", 1, stars = 2, maxStars = 2, requiredStars = 1, found = 90),
        row(4, "благословение", 2, stars = 3, maxStars = 3, requiredStars = 2, found = 95),
        row(5, "благоразумие", 2, stars = 1, maxStars = 3, requiredStars = 2, found = 70),
        row(6, "безрассудство", 3, stars = 3, maxStars = 3, requiredStars = 3, found = 100),
        row(7, "журналистика", 3, stars = 2, maxStars = 3, requiredStars = 3, found = 142,
            hints = 4, byLength = listOf(LengthStat(3, 20, 100), LengthStat(5, 122, 225))),
        row(8, "поздравление", 3, stars = 3, maxStars = 3, requiredStars = 3, found = 110),
        row(9, "благодарность", 4, stars = 4, maxStars = 4, requiredStars = 3, found = 120),
        row(10, "пуэрториканец", 4, stars = 3, maxStars = 4, requiredStars = 3, found = 111),
        row(11, "подстрекатель", 5, stars = 5, maxStars = 5, requiredStars = 4, found = 130),
        row(12, "проблематика", 5, stars = 4, maxStars = 5, requiredStars = 4, found = 121),
        row(13, "складирование", 5, stars = 5, maxStars = 5, requiredStars = 4, found = 131),
        row(14, "контрабандист", 6, stars = 5, maxStars = 5, requiredStars = 4, found = 140),
        row(15, "документалист", 6, stars = 4, maxStars = 5, requiredStars = 4, found = 141, hints = 19),
    )

    private fun statistics(rows: List<LevelStatsRow> = this.rows, foundWords: Int = 1_204): CampaignStatistics {
        val stages = stageBounds.mapIndexed { index, (first, last) ->
            StageStatsRows(index + 1, stageNames[index], first, last, rows.filter { it.number in first..last })
        }
        return CampaignStatistics(stars = 18, maxStars = 49, passedLevels = 6, totalLevels = 15,
            foundWords = foundWords, totalWords = rows.sumOf { it.total }, hintsShown = 23,
            stages = stages, levels = rows, achievementsEarned = 17, achievementsTotal = 99)
    }

    private fun show(rows: List<LevelStatsRow> = this.rows, foundWords: Int = 1_204) {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics(rows, foundWords)))
        compose.setContent { WordsTheme { StatsScreen(state.value) } }
    }

    @Test fun summaryRowsAndExpansionUseOneSetOfCoreNumbers() {
        show()
        compose.onNodeWithTag("stats_title").assertTextEquals("Статистика")
        compose.onNodeWithTag("stats_subtitle").assertTextEquals("Прогресс прохождения и достижения")
        compose.onNodeWithTag("stats_summary")
            .assertTextEquals("Звёзды 18/49 · Уровни 6/15 · Найдено слов на уровнях: 1 204")
        // Эти числа намеренно отличаются от суммы тестовых строк: UI читает итоги :core.
        compose.onNodeWithTag("stats_achievements_total")
            .assertTextEquals("Подсказок 23 · Достижения 17 из 99")
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_7")
        compose.onNodeWithTag("stats_row_7")
            .assertTextContains("Уровень 7 · ЖУРНАЛИСТИКА")
        assertMetrics(7, "142 / 325", "2 / 3")
        for (field in listOf("percent", "progress", "hints", "table", "required")) {
            compose.onNodeWithTag("stats_${field}_7", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("stats_toggle_7").performScrollTo().assertTextEquals("Подробнее ▾").performClick()
        assertMetrics(7, "142 / 325", "2 / 3")
        compose.onNodeWithTag("stats_percent_7", useUnmergedTree = true).assertTextEquals("43%")
        compose.onNodeWithTag("stats_hints_7", useUnmergedTree = true).assertTextEquals("Подсказок использовано: 4")
        compose.onNodeWithTag("stats_required_7", useUnmergedTree = true).assertTextEquals("для открытия: 3")
        compose.onNodeWithTag("stats_progress_7", useUnmergedTree = true).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(142f / 325, 0f..1f)))
        compose.onNodeWithTag("stats_table_7", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Осталось", useUnmergedTree = true).assertExists()
        // Таблица хранит прежние длины и остатки, включая общую сумму.
        for (value in listOf("20 / 100", "122 / 225", "142 / 325", "80", "103", "183")) {
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("stats_table_7")),
                useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag("stats_not_passed_7", useUnmergedTree = true)
            .assertTextEquals("Уровень ещё не пройден")
        compose.onNodeWithTag("stats_passed_7").assertDoesNotExist()

        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_5")
        compose.onNodeWithTag("stats_toggle_5").performScrollTo().performClick()
        compose.onNodeWithTag("stats_all_stars_5").assertDoesNotExist()

        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_1")
        assertMetrics(1, "0 / 325", "0 / 1")
        compose.onNodeWithTag("stats_toggle_1").performScrollTo().performClick()
        compose.onNodeWithTag("stats_hints_1", useUnmergedTree = true)
            .assertTextEquals("Подсказок использовано: 0")
        compose.onNodeWithTag("stats_not_passed_1", useUnmergedTree = true)
            .assertTextEquals("Уровень ещё не пройден")
        compose.onNodeWithTag("stats_passed_1").assertDoesNotExist()
    }

    private fun assertMetrics(level: Int, words: String, stars: String) {
        for ((metric, label, value) in listOf(
            Triple("words", "Найдено слов", words), Triple("stars", "Звёзды", stars),
        )) {
            val parent = hasAnyAncestor(hasTestTag("stats_metric_${metric}_$level"))
            compose.onNode(parent and hasText(label), useUnmergedTree = true).assertExists()
            compose.onNode(parent and hasText(value), useUnmergedTree = true).assertExists()
        }
    }

    @Test fun cardAndBottomLinkToggleButAchievementTouchOnlyOpensDialog() {
        show()
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_7")
        for (expanded in listOf(false, true)) {
            val description = if (expanded) "развёрнуто" else "свёрнуто"
            for (unmerged in listOf(false, true)) {
                compose.onNodeWithTag("stats_row_7", useUnmergedTree = unmerged)
                    .assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
            }
            compose.onNodeWithTag("stats_row_7").assertTextContains("Уровень 7 · ЖУРНАЛИСТИКА")
                .assertTextContains("Найдено слов").assertTextContains("142 / 325")
                .assertTextContains("Звёзды").assertTextContains("2 / 3").assertTextContains("Достижения")
            for ((name, title, earned) in listOf(
                Triple("expert", "Знаток", true), Triple("no_hints", "Без подсказок", false),
            )) {
                val icon = compose.onNodeWithTag("stats_achievement_7_$name")
                if (earned) icon.assertContentDescriptionEquals("Достижение «$title»: получено")
                else icon.assertContentDescriptionEquals(
                    "Достижение «$title»: не получено. Условие: Пройти уровень без подсказок")
                // Проверяем настоящий touch: семантический performClick не проверяет поглощение жеста ребёнком.
                icon.performScrollTo().performTouchInput { click() }
                compose.onNodeWithTag("achievement_dialog").assertIsDisplayed()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(if (earned) "Получено" else "Не получено").assertIsDisplayed()
                compose.onNodeWithText("Закрыть").performClick()
                compose.onNodeWithTag("stats_row_7").assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
            }
            compose.onNodeWithTag("stats_achievement_7_giant").assertDoesNotExist()
            if (!expanded) {
                // Нажатие на обычный текст обрабатывает вся карточка.
                compose.onNodeWithTag("stats_level_7", useUnmergedTree = true)
                    .performScrollTo().performTouchInput { click() }
            }
        }
        compose.onNodeWithTag("stats_toggle_7").performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertTextEquals("Свернуть ▴").performTouchInput { click() }
        compose.onNodeWithTag("stats_row_7").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "свёрнуто"))
        compose.onNodeWithTag("stats_table_7", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("stats_toggle_7").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("stats_row_7").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "развёрнуто"))
        compose.onNodeWithTag("stats_table_7", useUnmergedTree = true).assertExists()
    }

    @Test fun snapshotUpdatesWithoutRefresh() {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics()))
        compose.setContent { WordsTheme { StatsScreen(state.value) } }
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_7")
        compose.onNodeWithTag("stats_toggle_7").performScrollTo().performClick()
        compose.onNodeWithTag("stats_hints_7", useUnmergedTree = true).assertTextEquals("Подсказок использовано: 4")
        compose.runOnIdle {
            val changed = rows.map { if (it.number == 7) it.copy(hintsShown = 5) else it }
            state.value = StatsUiState(loading = false, statistics = statistics(changed))
        }
        compose.onNodeWithTag("stats_hints_7", useUnmergedTree = true).assertTextEquals("Подсказок использовано: 5")
        // Шапка могла уехать за пределы окна при прокрутке к уровню 7.
        compose.onNodeWithTag("stats_screen").performScrollToIndex(1)
        compose.onNodeWithTag("stats_summary")
            .assertTextEquals("Звёзды 18/49 · Уровни 6/15 · Найдено слов на уровнях: 1 204")
    }

    @Test fun timeMilestonesAreAbsentWhenRequiredThresholdIsTheMaximum() {
        val single = listOf(row(1, "чемпионство", 1, stars = 1, maxStars = 1, requiredStars = 1, found = 3))
        val stages = listOf(StageStatsRows(1, "Разминка", 1, 1, single))
        val stats = CampaignStatistics(stars = 1, maxStars = 1, passedLevels = 1, totalLevels = 1,
            foundWords = 3, totalWords = 325, hintsShown = 0, stages = stages, levels = single)
        compose.setContent { WordsTheme { StatsScreen(StatsUiState(loading = false, statistics = stats)) } }
        compose.onNodeWithTag("stats_toggle_1").performClick()
        compose.onNodeWithTag("stats_table_1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stats_passed_1").assertDoesNotExist()
        compose.onNodeWithTag("stats_all_stars_1").assertDoesNotExist()
    }

    @Test fun internalNotationAndRemovedAggregatesAreAbsent() {
        show()
        for (text in listOf("R", "M", "Этап", "Среднее", "Медиана", "Самый быстрый", "Самый долгий",
            "Активное время", "Пройден за:", "Все звёзды за:", "Время", "бездействия")) {
            compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
        }
        compose.onNodeWithTag("stats_title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun fifteenLevelsKeepStageNamesAndLayoutAt320DpWithDoubleFont() {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics()))
        compose.setContent {
            WordsTheme {
                val base = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(base, 2f)) {
                    Box(Modifier.width(320.dp)) { StatsScreen(state.value) }
                }
            }
        }
        compose.onNodeWithTag("stats_title").assertExists()
        for (stage in 1..6) {
            compose.onNodeWithTag("stats_screen").performScrollToKey("stats_stage_$stage")
            compose.onNodeWithTag("stats_stage_$stage")
                .assertTextContains(stageNames[stage - 1], substring = true)
        }
        for (level in 1..15) {
            compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_$level")
            compose.onNodeWithTag("stats_row_$level").assertExists()
                .assertTextContains("Уровень $level · ${rows[level - 1].word.uppercase()}")
            assertLevelTextFits(level)
        }
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_data_section")
        compose.onNodeWithTag("stats_data_heading").assertTextEquals("Данные")
        compose.onNodeWithTag("stats_reset_button").assertTextEquals("Начать заново")
    }

    @Test fun loadingReadErrorAndUnavailableCampaignDoNotPretendToBeZero() {
        val state = mutableStateOf(StatsUiState())
        compose.setContent { WordsTheme { StatsScreen(state.value) } }
        compose.onNodeWithTag("stats_loading").assertTextEquals("Загрузка статистики…")
        compose.runOnIdle { state.value = StatsUiState(loading = false, error = true) }
        compose.onNodeWithTag("stats_error").assertTextEquals("Не удалось прочитать статистику.")
        compose.onNodeWithTag("stats_summary").assertDoesNotExist()
        compose.runOnIdle { state.value = StatsUiState(loading = false) }
        compose.onNodeWithTag("stats_unavailable").assertTextEquals("Статистика доступна для кампании.")
        compose.onNodeWithTag("stats_summary").assertDoesNotExist()
    }

    private fun assertLevelTextFits(level: Int) {
        val textNodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                hasAnyAncestor(hasTestTag("stats_row_$level")), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Every summary field must expose readable text", textNodes.size >= 4)
        textNodes.forEach { node ->
            val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue("Text layout must be available", action(layouts))
            assertTrue("Text layout must not be empty", layouts.isNotEmpty())
            layouts.forEach { assertTextLayoutFits(it, "level=$level") }
        }
    }

    @Test fun resetDialogRequiresConfirmationIsReadableByTalkBackAndCancelIsFirstInFocusOrder() {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics()))
        var confirms = 0
        var cancels = 0
        compose.setContent {
            WordsTheme {
                StatsScreen(
                    state = state.value,
                    onRequestReset = { state.value = state.value.copy(resetConfirmVisible = true) },
                    onConfirmReset = { confirms++ },
                    onDismissReset = { cancels++; state.value = state.value.copy(resetConfirmVisible = false) },
                )
            }
        }
        compose.onNodeWithTag("stats_reset_dialog").assertDoesNotExist()
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_data_section")
        compose.onNodeWithTag("stats_data_heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("stats_reset_button").performClick()

        compose.onNodeWithTag("stats_reset_dialog")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Начать заново?"))
        compose.onNodeWithTag("stats_reset_dialog_message")
            .assertIsDisplayed()
            .assertTextEquals("Весь прогресс и найденные слова будут удалены. Это нельзя отменить.")
        compose.onNodeWithTag("stats_reset_cancel").assertIsDisplayed()
        compose.onNodeWithTag("stats_reset_confirm").assertIsDisplayed()

        // Кнопка отмены стоит первой визуально и первая в порядке обхода/фокуса.
        // В Compose фокус движется в порядке фокусируемых узлов дерева семантики;
        // BasicAlertDialog живёт в отдельном окне, поэтому надёжнее проверять сам
        // порядок узлов и их фокусируемость, а не moveFocus менеджера основной композиции.
        val cancelBounds = compose.onNodeWithTag("stats_reset_cancel").fetchSemanticsNode().boundsInRoot
        val confirmBounds = compose.onNodeWithTag("stats_reset_confirm").fetchSemanticsNode().boundsInRoot
        assertTrue(cancelBounds.left <= confirmBounds.left)
        for (tag in listOf("stats_reset_cancel", "stats_reset_confirm")) {
            assertNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.RequestFocus))
        }
        val traversalOrder = LinkedHashMap<String, Int>()
        fun SemanticsNode.collectFocusableOrder() {
            val tag = config.getOrNull(SemanticsProperties.TestTag)
            if (tag != null && tag in listOf("stats_reset_cancel", "stats_reset_confirm") &&
                config.getOrNull(SemanticsActions.RequestFocus) != null && tag !in traversalOrder
            ) {
                traversalOrder[tag] = traversalOrder.size
            }
            children.forEach { it.collectFocusableOrder() }
        }
        compose.onNodeWithTag("stats_reset_dialog", useUnmergedTree = true)
            .fetchSemanticsNode().collectFocusableOrder()
        assertEquals(0, traversalOrder.getValue("stats_reset_cancel"))
        assertEquals(1, traversalOrder.getValue("stats_reset_confirm"))

        compose.onNodeWithTag("stats_reset_cancel").performClick()
        compose.onNodeWithTag("stats_reset_dialog").assertDoesNotExist()
        assertEquals(0, confirms)
        assertEquals(1, cancels)

        compose.onNodeWithTag("stats_reset_button").performClick()
        compose.onNodeWithTag("stats_reset_confirm").performClick()
        assertEquals(1, confirms)
    }

    @Test fun resetThroughViewModelRequiresConfirmationAndClearsProgressKeepsWelcome() = runTest {
        val dir = temporary.newFolder("stats-reset")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fun store(name: String) = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                    File(dir, name).absolutePath.toPath()
                },
                scope = scope,
            )
            val progressStore = store("progress.json")
            val campaignJson = """{"version":2,"countMinLength":4,
                "stages":[{"number":1,"firstLevel":1,"lastLevel":2}],
                "levels":[
                    {"number":1,"word":"картотека","stage":1,"requiredStars":1,"tasks":[{"id":"c1","type":"T1","n":1}]},
                    {"number":2,"word":"трактор","stage":1,"requiredStars":1,"tasks":[{"id":"c2","type":"T1","n":1}]}
                ]}"""
            val campaign = CampaignJson.decode(campaignJson)
            val index = WordIndex(listOf("крот", "кора"))
            val progressRepo = ProgressRepository(
                dataStore = progressStore,
                installEvidence = { InstallEvidence.FRESH_INSTALL },
                hadLocalStateAtStartup = false,
            )
            assertEquals(WelcomeState.SHOW, progressRepo.resolveWelcomeState())
            assertTrue(progressRepo.markWelcomeDone())
            progressRepo.migrateCampaign(campaign, index)
            progressRepo.submitCampaignAnswer(campaign, 1, "крот", index)

            val vm = StatsViewModel(
                levels = LevelsRepository(readCampaign = { campaignJson }),
                progress = progressRepo,
                words = WordRepository(readWords = { "картотека\nтрактор\nкрот\nкора" }),
                stageNames = mapOf(1 to "Разминка"),
            )
            val returned = mutableStateOf(0)
            compose.setContent {
                val state = vm.uiState.collectAsState().value
                androidx.compose.runtime.LaunchedEffect(state.resetCompleted) {
                    if (state.resetCompleted) returned.value++
                }
                WordsTheme {
                    StatsScreen(
                        state = state,
                        onRequestReset = vm::onRequestReset,
                        onConfirmReset = vm::onConfirmReset,
                        onDismissReset = vm::onDismissReset,
                    )
                }
            }
            compose.waitUntil(10_000) { vm.uiState.value.statistics != null }
            compose.onNodeWithTag("stats_screen").performScrollToKey("stats_data_section")
            compose.onNodeWithTag("stats_reset_button").performClick()
            compose.onNodeWithTag("stats_reset_cancel").performClick()

            assertEquals(0, returned.value)
            assertEquals(2, progressRepo.observeCampaignProgress().first().highestUnlockedLevel)

            compose.onNodeWithTag("stats_reset_button").performClick()
            compose.onNodeWithTag("stats_reset_confirm").performClick()
            compose.waitUntil(10_000) { returned.value == 1 }

            val afterProgress = progressRepo.observeCampaignProgress().first()
            assertEquals(CampaignProgress(), afterProgress)
            assertEquals(1, afterProgress.highestUnlockedLevel)
            assertEquals(WelcomeState.DONE, progressRepo.resolveWelcomeState())
            assertTrue(
                progressStore.data.first().asMap().keys.none {
                    it.name == "campaignProgressV2Archive" || it.name.startsWith("campaignProgressV2Archive.")
                },
            )
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}

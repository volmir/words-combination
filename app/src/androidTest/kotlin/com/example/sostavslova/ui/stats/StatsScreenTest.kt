package com.example.sostavslova.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import com.example.sostavslova.data.PlayTimeRepository
import com.example.sostavslova.data.ProgressRepository
import com.example.sostavslova.data.WordRepository
import com.example.sostavslova.data.jsonstore.PreferenceDataStoreFactory
import com.example.sostavslova.data.jsonstore.PreferencesSerializer
import com.example.sostavslova.data.jsonstore.stringPreferencesKey
import com.example.sostavslova.time.PlayTimeTarget
import com.example.sostavslova.time.PlayTimeTracker
import com.example.sostavslova.ui.assertTextLayoutFits
import com.example.sostavslova.ui.theme.WordsTheme
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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
        activeMs: Long? = null,
        toRMs: Long? = null,
        toFullMs: Long? = null,
        hints: Int = 0,
        status: LevelTimeStatus = if (activeMs == null) LevelTimeStatus.Unplayed else LevelTimeStatus.Measured,
        byLength: List<LengthStat> = listOf(LengthStat(3, 0, 100), LengthStat(4, 0, 100),
            LengthStat(5, 0, total - 200)),
    ) = LevelStatsRow(number, word, stageNames[stage - 1], stars, maxStars, requiredStars, found, total,
        activeMs, toRMs, toFullMs, hints, byLength, status)

    private val rows = listOf(
        row(1, "чемпионство", 1, maxStars = 1, requiredStars = 1),
        row(2, "вегетарианец", 1, stars = 1, maxStars = 1, requiredStars = 1, found = 80),
        row(3, "аккумулятор", 1, stars = 2, maxStars = 2, requiredStars = 1, found = 90),
        row(4, "благословение", 2, stars = 3, maxStars = 3, requiredStars = 2, found = 95),
        row(5, "благоразумие", 2, stars = 1, maxStars = 3, requiredStars = 2, found = 70),
        row(6, "безрассудство", 3, stars = 3, maxStars = 3, requiredStars = 3, found = 100),
        row(7, "журналистика", 3, stars = 2, maxStars = 3, requiredStars = 3, found = 142,
            activeMs = 2_280_000, toRMs = 1_200_000, hints = 4,
            byLength = listOf(LengthStat(3, 20, 100), LengthStat(5, 122, 225))),
        row(8, "поздравление", 3, stars = 3, maxStars = 3, requiredStars = 3, found = 110),
        row(9, "благодарность", 4, stars = 4, maxStars = 4, requiredStars = 3, found = 120),
        row(10, "пуэрториканец", 4, stars = 3, maxStars = 4, requiredStars = 3, found = 111),
        row(11, "подстрекатель", 5, stars = 5, maxStars = 5, requiredStars = 4, found = 130),
        row(12, "проблематика", 5, stars = 4, maxStars = 5, requiredStars = 4, found = 121),
        row(13, "складирование", 5, stars = 5, maxStars = 5, requiredStars = 4, found = 131),
        row(14, "контрабандист", 6, stars = 5, maxStars = 5, requiredStars = 4, found = 140),
        row(15, "документалист", 6, stars = 4, maxStars = 5, requiredStars = 4, found = 141,
            activeMs = 60_000, toRMs = 50_000, hints = 19),
    )

    private fun statistics(rows: List<LevelStatsRow> = this.rows, foundWords: Int = 1_204): CampaignStatistics {
        val stages = stageBounds.mapIndexed { index, (first, last) ->
            StageStatsRows(index + 1, stageNames[index], first, last, rows.filter { it.number in first..last })
        }
        return CampaignStatistics(stars = 18, maxStars = 49, passedLevels = 6, totalLevels = 15,
            foundWords = foundWords, totalWords = rows.sumOf { it.total }, hintsShown = 23,
            totalTimeMs = java.math.BigInteger.valueOf(8_040_000), measuredLevelCount = 1,
            stages = stages, levels = rows)
    }

    private fun show(rows: List<LevelStatsRow> = this.rows, foundWords: Int = 1_204) {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics(rows, foundWords)))
        compose.setContent { WordsTheme { StatsScreen(state.value) } }
    }

    @Test fun summaryRowsAndExpansionUseOneSetOfCoreNumbers() {
        show()
        compose.onNodeWithTag("stats_summary")
            .assertTextEquals("Звёзды 18/49 · Уровни 6/15 · Слов 1 204")
        compose.onNodeWithTag("stats_time_summary").assertTextEquals("Время 2 ч 14 мин · Подсказок 23")
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_7")
        compose.onNodeWithTag("stats_level_7")
            .assertTextContains("7 · ЖУРНАЛИСТИКА")
            .assertTextContains("получено 2 из 3")
            .assertTextContains("для открытия: 3")
            .assertTextContains("142 из 325 слов")
            .assertTextContains("43%")
            .assertTextContains("38 мин · подсказок 4")
        compose.onNodeWithTag("stats_expand_7").performClick()
        compose.onNodeWithTag("stats_table_7").assertExists()
        compose.onNodeWithText("Осталось").assertExists()
        // Уровень 7 ещё не пройден: 2 звезды при пороге 3.
        compose.onNodeWithTag("stats_not_passed_7").assertTextEquals("Уровень ещё не пройден")
        compose.onNodeWithTag("stats_passed_7").assertDoesNotExist()
        // При M = R отдельной строки «Все звёзды» нет: это проверяет другой тест.

        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_5")
        compose.onNodeWithTag("stats_expand_5").performClick()
        compose.onNodeWithTag("stats_all_stars_5").assertTextEquals("Все звёзды ещё не получены")

        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_1")
        compose.onNodeWithTag("stats_level_1")
            .assertTextContains("0 из 325 слов")
            .assertTextContains("получено 0 из 1")
        compose.onNodeWithTag("stats_time_1", useUnmergedTree = true).assertTextEquals("—")
        compose.onNodeWithTag("stats_expand_1").performClick()
        compose.onNodeWithTag("stats_not_passed_1").assertTextEquals("Уровень ещё не пройден")
        compose.onNodeWithTag("stats_passed_1").assertDoesNotExist()
    }

    @Test fun snapshotUpdatesWithoutRefreshAndMeasuredTimeWins() {
        val state = mutableStateOf(StatsUiState(loading = false, statistics = statistics()))
        compose.setContent { WordsTheme { StatsScreen(state.value) } }
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_level_7")
        compose.onNodeWithTag("stats_time_7", useUnmergedTree = true).assertTextEquals("38 мин · подсказок 4")
        compose.runOnIdle {
            val changed = rows.map { if (it.number == 7) it.copy(hintsShown = 5) else it }
            state.value = StatsUiState(loading = false, statistics = statistics(changed))
        }
        compose.onNodeWithTag("stats_time_7", useUnmergedTree = true).assertTextEquals("38 мин · подсказок 5")
        // Шапка могла уехать за пределы окна при прокрутке к уровню 7.
        compose.onNodeWithTag("stats_screen").performScrollToIndex(1)
        compose.onNodeWithTag("stats_time_summary").assertTextEquals("Время 2 ч 14 мин · Подсказок 23")
    }

    @Test fun allStarsLineIsHiddenWhenRequiredThresholdIsTheMaximum() {
        val single = listOf(row(1, "чемпионство", 1, stars = 1, maxStars = 1, requiredStars = 1, found = 3))
        val stages = listOf(StageStatsRows(1, "Разминка", 1, 1, single))
        val stats = CampaignStatistics(stars = 1, maxStars = 1, passedLevels = 1, totalLevels = 1,
            foundWords = 3, totalWords = 325, hintsShown = 0, totalTimeMs = java.math.BigInteger.ZERO,
            measuredLevelCount = 0, stages = stages, levels = single)
        compose.setContent { WordsTheme { StatsScreen(StatsUiState(loading = false, statistics = stats)) } }
        compose.onNodeWithTag("stats_expand_1").performClick()
        compose.onNodeWithTag("stats_passed_1").assertTextEquals("Пройден за —")
        compose.onNodeWithTag("stats_all_stars_1").assertDoesNotExist()
    }

    @Test fun internalNotationAndRemovedAggregatesAreAbsent() {
        show()
        for (text in listOf("R", "M", "Этап", "Среднее", "Медиана", "Самый быстрый", "Самый долгий",
            "Активное время", "Пройден за:", "Все звёзды за:")) {
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
            compose.onNodeWithTag("stats_level_$level").assertExists()
                .assertTextContains("$level · ${rows[level - 1].word.uppercase()}")
            assertLevelTextFits(level)
        }
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_time_note")
        compose.onNodeWithTag("stats_time_note").assertTextContains("до включения учёта", substring = true)
        compose.onNodeWithTag("stats_screen").performScrollToKey("stats_idle_note")
        val expectedIdleSeconds = (PlayTimeTracker.DEFAULT_IDLE_TIMEOUT_MS / 1_000L).toInt()
        compose.onNodeWithTag("stats_idle_note")
            .assertTextEquals("Если игра не получает касаний больше $expectedIdleSeconds секунд, время не считается.")
        assertTextLayoutFitsNode("stats_idle_note")
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
                hasAnyAncestor(hasTestTag("stats_level_$level")), useUnmergedTree = true,
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

    private fun assertTextLayoutFitsNode(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val action = requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue("Text layout must be available", action(layouts))
        assertTrue("Text layout must not be empty", layouts.isNotEmpty())
        layouts.forEach { assertTextLayoutFits(it, tag) }
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
            .assertTextEquals("Весь прогресс, найденные слова и время будут удалены. Это нельзя отменить.")
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

    @Test fun resetThroughViewModelRequiresConfirmationAndClearsProgressAndTimeKeepingWelcome() = runTest {
        val dir = temporary.newFolder("stats-reset")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = AtomicLong(0L)
        try {
            fun store(name: String) = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                    File(dir, name).absolutePath.toPath()
                },
                scope = scope,
            )
            val progressStore = store("progress.json")
            val timeStore = store("time.json")
            val campaignJson = """{"version":2,"countMinLength":4,
                "stages":[{"number":1,"firstLevel":1,"lastLevel":2}],
                "levels":[
                    {"number":1,"word":"картотека","stage":1,"requiredStars":1,"tasks":[{"id":"c1","type":"T1","n":1}]},
                    {"number":2,"word":"трактор","stage":1,"requiredStars":1,"tasks":[{"id":"c2","type":"T1","n":1}]}
                ]}"""
            val campaign = CampaignJson.decode(campaignJson)
            val index = WordIndex(listOf("крот", "кора"))
            val timeRepo = PlayTimeRepository(timeStore)
            val tracker = PlayTimeTracker(timeRepo, scope, clock::get)
            val progressRepo = ProgressRepository(
                dataStore = progressStore,
                installEvidence = { InstallEvidence.FRESH_INSTALL },
                hadLocalStateAtStartup = false,
                onCampaignCommitted = tracker::checkpointProgress,
            )
            assertEquals(WelcomeState.SHOW, progressRepo.resolveWelcomeState())
            assertTrue(progressRepo.markWelcomeDone())
            progressRepo.migrateCampaign(campaign, index)
            tracker.setRoute("entry1", 1)
            tracker.updateLevel("entry1", PlayTimeTarget(campaign, 1, 0))
            tracker.setLevelResumed("entry1", true)
            tracker.setActivityResumed(true)
            tracker.setWindowFocused("activity", true)
            clock.set(12_000L)
            progressRepo.submitCampaignAnswer(campaign, 1, "крот", index)
            tracker.detachLevel("entry1")
            tracker.setRoute("stats", null)
            tracker.awaitSaved()

            val vm = StatsViewModel(
                levels = LevelsRepository(readCampaign = { campaignJson }),
                time = timeRepo,
                progress = progressRepo,
                words = WordRepository(readWords = { "картотека\nтрактор\nкрот\nкора" }),
                stageNames = mapOf(1 to "Разминка"),
                playTimeTracker = tracker,
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
            assertEquals(12_000L, timeRepo.snapshot(campaign).getValue(1).cumulativeActiveMs)

            compose.onNodeWithTag("stats_reset_button").performClick()
            compose.onNodeWithTag("stats_reset_confirm").performClick()
            compose.waitUntil(10_000) { returned.value == 1 }

            val afterProgress = progressRepo.observeCampaignProgress().first()
            assertEquals(CampaignProgress(), afterProgress)
            assertEquals(1, afterProgress.highestUnlockedLevel)
            assertTrue(timeRepo.snapshot(campaign).values.all { !it.initialized && it.cumulativeActiveMs == 0L })
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

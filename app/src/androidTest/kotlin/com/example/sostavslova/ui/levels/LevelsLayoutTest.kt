package com.example.sostavslova.ui.levels

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sostavslova.R
import com.example.sostavslova.core.Stage
import com.example.sostavslova.core.LevelAchievement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.sostavslova.ui.theme.WordsTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LevelsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val campaign = JSONObject(context.assets.open("campaign.json").bufferedReader().use { it.readText() })
        val levelsJson = campaign.getJSONArray("levels")
        val items = List(levelsJson.length()) { i ->
            val row = levelsJson.getJSONObject(i)
            LevelUiState(row.getInt("number"), row.getString("word"), 0,
                unlocked = i == 0, maxStars = row.getJSONArray("tasks").length(),
                requiredStars = row.getInt("requiredStars"), stage = row.getInt("stage"))
        }
        val stagesJson = campaign.getJSONArray("stages")
        val stages = List(stagesJson.length()) { i ->
            val row = stagesJson.getJSONObject(i)
            Stage(row.getInt("number"), row.getInt("firstLevel"), row.getInt("lastLevel"))
        }
        val state = mutableStateOf(LevelsUiState(loading = false, levels = items, stages = stages))
        var selected = ""
        compose.setContent {
            WordsTheme {
                LevelsScreen(state.value, { selected = it }, {})
            }
        }
        compose.onNodeWithTag("level_card_1").assertIsEnabled()
            .assert(hasContentDescription("Уровень недоступен").not())
        val threshold = context.getString(R.string.level_stars_threshold, items.first().requiredStars)
        assertStars(context.getString(R.string.level_stars_label),
            context.resources.getQuantityString(R.plurals.level_stars_progress, items.first().maxStars,
                0, items.first().maxStars) + " · " + threshold)
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assert(hasContentDescription("Уровень недоступен"))
        // Ряд звёзд на карте только показывает прогресс: «Задания» здесь вводит в заблуждение.
        compose.onAllNodesWithContentDescription("Задания").assertCountEquals(0)
        compose.onAllNodesWithText("Для прохождения", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Можно добрать", substring = true).assertCountEquals(0)
        val first = compose.onNodeWithTag("level_card_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("level_card_2").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("levels_screen").fetchSemanticsNode().boundsInRoot
        if (screen.width / compose.density.density < 600f) assertTrue(second.top > first.top)
        else { assertEquals(first.top, second.top, 1f); assertTrue(second.left > first.left) }
        checkLayout()
        compose.onNodeWithTag("level_card_1").performClick()
        compose.runOnIdle { assertEquals("чемпионство", selected) }
        compose.runOnIdle { state.value = state.value.copy(levels = items.mapIndexed { i, item ->
            if (i == 0) item.copy(stars = item.maxStars)
            else if (i == 1) item.copy(unlocked = true) else item
        }) }
        val maxStars = items.first().maxStars
        val starsDescription = context.resources.getQuantityString(R.plurals.level_stars_progress, maxStars, maxStars, maxStars) +
            " · " + threshold
        assertStars(context.getString(R.string.level_stars_label), starsDescription)
        compose.onNodeWithTag("level_card_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, starsDescription))
        compose.onNodeWithTag("level_card_2").assertIsEnabled()
            .assert(hasContentDescription("Уровень недоступен").not())
        compose.onNodeWithTag("levels_grid").performScrollToKey("stage_6")
        compose.onNodeWithTag("levels_stage_6").assertTextEquals("Эксперт · уровни 14–15")
        compose.onNodeWithTag("levels_grid").performScrollToKey(items.last().word)
        compose.onNodeWithTag("level_card_15").assertExists()
        compose.onAllNodesWithTag("level_card_16").assertCountEquals(0)
        checkLayout()
    }

    @Test fun achievementLineFitsNarrowCardAtLargeFontBelowStars() {
        val all = LevelAchievement.entries.toSet()
        val item = LevelUiState(1, "электростанция", 3, true, 5, 2,
            achievements = all, achievementsAvailable = all)
        compose.setContent { WordsTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(280.dp)) {
                    LevelsScreen(LevelsUiState(loading = false, levels = listOf(item)), {}, {})
                }
            }
        } }
        val line = compose.onNodeWithTag("level_achievements_1", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals("Достижения: 8 из 8").fetchSemanticsNode().boundsInRoot
        val stars = compose.onNodeWithContentDescription("Звёзды").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("level_card_1").assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(line.top >= stars.bottom)
        assertTrue(line.left >= card.left && line.right <= card.right && line.bottom <= card.bottom)
        compose.onNodeWithTag("level_card_1").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Получено 3 из 5 звёзд · порог открытия: 2. Достижения: 8 из 8"))
        checkLayout()
    }

    private fun assertStars(label: String, description: String) {
        val state = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)
        for (unmerged in listOf(false, true)) {
            val card = compose.onNodeWithTag("level_card_1", useUnmergedTree = unmerged)
            card.assert(state)
            card.printToLog("G04_${if (unmerged) "unmerged" else "merged"}")
            // StarsRow — самостоятельный merging-node внутри кнопки уровня.
            compose.onNode(hasContentDescription(label) and hasAnyAncestor(hasTestTag("level_card_1")),
                useUnmergedTree = unmerged).assert(state)
        }
        // Проверяем и экспорт в Android accessibility tree, который читает TalkBack.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 5_000)
        fun exposesStars(node: AccessibilityNodeInfo, parentState: CharSequence? = null): Boolean {
            val nodeState = AccessibilityNodeInfoCompat.wrap(node).stateDescription ?: parentState
            // Android может экспортировать label в виртуальный дочерний узел узла со state.
            if (node.isVisibleToUser && node.contentDescription?.toString() == label &&
                nodeState?.toString() == description) return true
            return (0 until node.childCount).any { index ->
                node.getChild(index)?.let { exposesStars(it, nodeState) } == true
            }
        }
        val root = automation.rootInActiveWindow
        val exposed = root?.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName && exposesStars(root)
        assertTrue("Android accessibility: $label, $description", exposed)
    }

    private fun checkLayout() {
        for (node in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            for (layout in layouts) {
                val text = layout.layoutInput.text.text
                assertFalse(text, layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse(text, layout.isLineEllipsized(line))
                    assertTrue(text, layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue(text, layout.getLineLeft(line) >= -1f)
                    assertTrue(text, layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        for (node in compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()) {
            val minimum = 48f * compose.density.density - 1f
            assertTrue(node.size.toString(), node.size.width >= minimum && node.size.height >= minimum)
        }
    }
}

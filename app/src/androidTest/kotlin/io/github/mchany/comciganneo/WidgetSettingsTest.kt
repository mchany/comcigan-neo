package io.github.mchany.comciganneo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.widgets.WidgetRenderer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(matcher: SemanticsMatcher) { compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun palettePersistsAndWidgetTapsOpenCorrectTodayTab() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        seedWidgetData(context)
        val repository = (context.applicationContext as NeoApplication).repository
        waitFor(hasText("오늘"))
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("로즈"))
        compose.onNodeWithText("로즈").performScrollTo().performClick()
        waitFor(hasText("로즈") and isSelected())
        compose.onNodeWithText("로즈").assertIsSelected()
        for (label in listOf("시간표 2×2", "시간표 4×2", "급식 2×2", "급식 4×2")) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설정"))
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("로즈"))
        compose.onNodeWithText("로즈").performScrollTo().assertIsSelected()
        assertEquals(WidgetPalette.ROSE, repository.preferences.first().widgetPalette)
        WidgetRenderer.openApp(context, WidgetKind.MEALS, false).send()
        waitFor(hasText("오늘은 무슨 메뉴?"))
        compose.onNodeWithText("점심검증밥", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 날짜").performScrollTo().performClick()
        waitFor(hasText("등록된 중식 정보가 없어요.", substring = true))
        WidgetRenderer.openApp(context, WidgetKind.MEALS, false).send()
        waitFor(hasText("점심검증밥", substring = true))
        WidgetRenderer.openApp(context, WidgetKind.TIMETABLE, false).send()
        waitFor(hasTestTag("today-viewport"))
    }
}

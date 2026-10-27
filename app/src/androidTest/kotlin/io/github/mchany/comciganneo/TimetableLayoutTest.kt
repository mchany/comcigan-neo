package io.github.mchany.comciganneo

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class TimetableLayoutTest {
    @get:Rule val compose = createComposeRule()
    private data class Viewport(val width: Int, val height: Int, val fontScale: Float = 1f)
    private val cases = listOf(Viewport(320, 400), Viewport(360, 500), Viewport(411, 700),
        Viewport(640, 190), Viewport(800, 900), Viewport(320, 400, 1.6f), Viewport(360, 500, 2f))
    private val selection = ClassSelection(School(123, "레이아웃검증학교", "대전"), 2, 1)
    private val now = ZonedDateTime.of(weekStart().plusDays(1), java.time.LocalTime.NOON, SchoolZone)
    private val snapshot = TimetableSnapshot(selection, weekStart().toString(), fetchedAt = System.currentTimeMillis(),
        classCounts = listOf(1, 1), slots = (0..4).flatMap { day -> (1..8).map { period ->
            LessonSlot(weekStart().plusDays(day.toLong()).toString(), period, "09:00",
                Lesson("과목$period", "교사$period"), changed = period == 3)
        } })

    @Test fun everyWeeklyCellFitsWithoutScrollingAcrossViewports() = checkViewports(weekly = true)
    @Test fun everyTodayPeriodFitsAndTeacherStaysOnSameLine() = checkViewports(weekly = false)

    private fun checkViewports(weekly: Boolean) {
        var viewport by mutableStateOf(cases.first())
        val state = NeoState(initializing = false, preferences = AppPreferences(selection), snapshot = snapshot)
        compose.setContent {
            val metrics = LocalContext.current.resources.displayMetrics
            val scale = minOf((metrics.widthPixels - 40f) / viewport.width, (metrics.heightPixels - 160f) / viewport.height)
                .coerceAtMost(LocalDensity.current.density)
            CompositionLocalProvider(LocalDensity provides Density(scale, viewport.fontScale)) {
                NeoTheme {
                    Box(Modifier.requiredSize(viewport.width.dp, viewport.height.dp).testTag("layout-frame")) {
                        if (weekly) WeekScreen(state, {}, {}) {} else TodayScreen(state, now, {}) {}
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { viewport = case }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag(if (weekly) "week-viewport" else "today-viewport").fetchSemanticsNode().boundsInRoot
            val tags = if (weekly) (0..4).flatMap { day -> (1..8).map { period -> "week-cell-${weekStart().plusDays(day.toLong())}-$period" } }
                else (1..8).map { "today-period-$it" }
            tags.forEach { tag ->
                val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
                val bounds = node.boundsInRoot
                assertTrue("$tag outside viewport $case: $bounds vs $frame", bounds.height > 0 && bounds.width > 0 &&
                    bounds.top >= frame.top - 1 && bounds.bottom <= frame.bottom + 1 &&
                    bounds.left >= frame.left - 1 && bounds.right <= frame.right + 1)
            }
            compose.onNodeWithTag("timetable-sync").assertIsDisplayed()
            compose.onNodeWithText("오늘 중식").assertDoesNotExist()
            if (!weekly) {
                compose.onNodeWithText("과목8 교사8").assertIsDisplayed()
                compose.onNodeWithText("과목8 교사8").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(action(results))
                    assertTrue(results.all { it.lineCount == 1 })
                }
            }
        }
    }
}

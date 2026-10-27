package io.github.mchany.comciganneo

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.model.weekStart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.format.DateTimeFormatter

/** Opt-in real-server test. Requires -PemulatorSmoke=true to isolate user settings. */
@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 45_000) {
        compose.waitUntil(timeoutMillis = timeout) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "debug-screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun onboardingWeekThemeAndRecreation() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".smoke")) {
            "Run with -PemulatorSmoke=true so normal app settings are preserved"
        }
        waitFor(hasText("학교 검색") or hasText("설정"))
        if (compose.onAllNodesWithText("학교 검색").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("설정").performClick()
            compose.onNodeWithText("변경").performClick()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("중일고등학교")
        compose.onNodeWithText("학교 검색").performClick()
        waitFor(hasText("중일고등학교"))
        compose.onNodeWithText("중일고등학교").performScrollTo().performClick()
        waitFor(hasText("이 학급으로 시작"))
        compose.onNodeWithText("이 학급으로 시작").performScrollTo().performClick()
        waitFor(hasText("오늘"))
        compose.onNodeWithText("오늘").performClick()
        waitFor(hasTestTag("today-viewport"))
        screenshot("01-today")

        compose.onNodeWithText("주간", useUnmergedTree = false).performClick()
        waitFor(hasTestTag("week-table-${weekStart()}"))
        compose.onNodeWithText("금 ${weekStart().plusDays(4).format(DateTimeFormatter.ofPattern("M.d"))}").assertIsDisplayed()
        screenshot("02-week")
        compose.onNodeWithText("다음 주").performClick()
        waitFor(hasTestTag("week-table-${weekStart(offset = 1)}"))
        screenshot("03-next-week")
        compose.onNodeWithContentDescription("목록으로 보기").performClick()
        waitFor(hasText("월요일", substring = true))
        screenshot("04-week-list")

        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("어둡게").performClick()
        waitFor(hasText("어둡게") and isSelected())
        if (compose.onAllNodes(hasContentDescription("교사명 표시") and isOn()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("교사명 표시").performClick()
        }
        waitFor(hasContentDescription("교사명 표시") and isOff())
        compose.onNodeWithContentDescription("교사명 표시").assertIsOff()
        screenshot("05-dark-settings")

        compose.onNodeWithText("오늘", useUnmergedTree = false).performClick()
        waitFor(hasTestTag("today-viewport"))
        screenshot("06-dark-today")
        compose.activityRule.scenario.recreate()
        waitFor(hasTestTag("today-viewport"))
        screenshot("07-recreated")
    }
}

package io.github.mchany.comciganneo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.data.AppRepository
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on cleared .smoke app data; never clear the user's normal app. */
@RunWith(AndroidJUnit4::class)
class InitialSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun freshSetupResumesAndCanBeCompletedLater() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".smoke"))
        val repository = AppRepository(context)
        assertNull("Clear only .smoke data before this test", repository.preferences.first().selection)
        waitFor("초기 설정 · 1/3")
        val selection = ClassSelection(School(424242, "초기설정검증학교", "대전"), 1, 1)
        // Simulate confirmation of a timetable class without depending on a live server.
        repository.select(selection)
        waitFor("급식 학교 선택")
        assertTrue(repository.preferences.first().initialSetupPending)
        compose.onNodeWithText("급식 학교 이름").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitFor("급식 학교 선택")
        compose.onNodeWithText("나중에 설정").performScrollTo().performClick()
        waitFor("초기 설정 · 3/3")
        assertTrue(repository.preferences.first().notificationSetupPending)
        compose.onNodeWithContentDescription("시간표 알림").assertIsOff()
        compose.onNodeWithContentDescription("급식 알림").assertIsOff()
        compose.activityRule.scenario.recreate()
        waitFor("초기 설정 · 3/3")
        compose.onNodeWithText("설정 완료").performScrollTo().performClick()
        waitFor("오늘")
        assertFalse(repository.preferences.first().initialSetupPending)
        assertFalse(repository.preferences.first().notificationSetupPending)
        compose.onNodeWithText("급식", useUnmergedTree = false).performClick()
        waitFor("오늘은 무슨 메뉴?")
        compose.onNodeWithText("설정 탭의 급식 > 연결 관리에서 급식 학교를 선택해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("급식 학교 연결").assertDoesNotExist()
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithText("연결 관리").performScrollTo().performClick()
        waitFor("급식 학교 연결")
        compose.activityRule.scenario.recreate()
        waitFor("오늘")
        compose.onNodeWithText("급식 학교 선택").assertDoesNotExist()

        // Linking, unlinking, changing class and clearing caches must not restart onboarding.
        val school = NeisSchoolRef("G10", "fixture", "초기설정검증학교", "검증교육청", "검증 주소")
        repository.setMealSchool(selection.school.code, school)
        repository.setMealSchool(selection.school.code, null)
        repository.select(selection.copy(classNumber = 2))
        repository.clearCache()
        compose.activityRule.scenario.recreate()
        waitFor("오늘")
        assertFalse(repository.preferences.first().initialSetupPending)
        compose.onNodeWithText("급식 학교 선택").assertDoesNotExist()
    }
}

package io.github.mchany.comciganneo

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.data.*
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses synthetic fresh caches in the separate .smoke package; never needs a NEIS key. */
@RunWith(AndroidJUnit4::class)
class MealUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "debug-screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun cachedMealsDatesDetailsAndRecreation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".smoke"))
        val selection = ClassSelection(School(424242, "급식검증고등학교", "대전"), 1, 1)
        val school = NeisSchoolRef("G10", "fixture", "급식검증고등학교", "검증교육청", "검증 주소 123")
        val today = schoolToday()
        val now = System.currentTimeMillis()
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, "timetables.db").addMigrations(Migration1To2).build()
        try {
            val timetable = TimetableSnapshot(selection, weekStart().toString(), fetchedAt = now,
                classCounts = listOf(1), slots = listOf(LessonSlot(today.toString(), 1, "09:00", Lesson("검증수업", "선생님"))))
            database.snapshots().save(SnapshotEntity(selection.school.code, 1, 1, timetable.weekStart, Json.encodeToString(timetable)))
            val entries = listOf(MealType.LUNCH, MealType.DINNER).map { type -> MealEntry(today.toString(), type.code, type.label,
                "검증밥 100g", listOf(if (type == MealType.LUNCH) "검증밥 100g (5.6)" else "검증석식 (1.2)"), "700.0 Kcal",
                nutrition = "단백질(g): 20", origin = "쌀: 국내산") }
            val allEntries = entries + entries.last().copy(date = today.plusWeeks(1).toString(), menu = listOf("다음주검증석식"))
            for (week in setOf(weekStart(today), weekStart(today.plusDays(1)), weekStart(today.plusWeeks(1)))) {
                val snapshot = MealSnapshot(school, week.toString(), now, allEntries.filter { weekStart(java.time.LocalDate.parse(it.date)) == week })
                database.meals().save(MealSnapshotEntity(school.officeCode, school.schoolCode, snapshot.weekStart, Json.encodeToString(snapshot)))
            }
        } finally { database.close() }
        val repository = AppRepository(context)
        repository.setMealSchool(selection.school.code, school)
        repository.select(selection)
        repository.finishInitialSetup()
        repository.theme(ThemeMode.LIGHT)
        waitFor(hasText("검증수업 선생님"))
        compose.onNodeWithText("오늘 중식").assertDoesNotExist()
        compose.onNodeWithText("나이스 인증키 설정").assertDoesNotExist()
        screenshot("meal-01-today")
        compose.onNodeWithText("급식", useUnmergedTree = false).performClick()
        waitFor(hasText("오늘은 무슨 메뉴?"))
        compose.onNodeWithText("급식 학교 변경").assertDoesNotExist()
        compose.onNodeWithText("급식 학교 연결").assertDoesNotExist()
        compose.onNodeWithText("검증 주소 123").assertDoesNotExist()
        compose.onNodeWithText("검증밥 100g (5.6)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("영양정보").performScrollTo().performClick()
        compose.onNodeWithText("단백질(g): 20").performScrollTo().assertIsDisplayed()
        screenshot("meal-02-details")
        compose.onNode(hasText("석식") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("검증석식 (1.2)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("다음 날짜").performScrollTo().performClick()
        waitFor(hasText("등록된 석식 정보가 없어요.", substring = true))
        screenshot("meal-03-empty")
        compose.onNodeWithText("오늘로 이동").performScrollTo().performClick()
        compose.onNodeWithText("검증석식 (1.2)").performScrollTo().assertIsDisplayed()
        // Changing weeks must display the selected week's cache and return to today.
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[NeoViewModel::class.java].mealDate(today.plusWeeks(1))
        }
        waitFor(hasText("다음주검증석식"))
        compose.onNodeWithText("다음주검증석식").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("검증석식 (1.2)").assertDoesNotExist()
        compose.onNodeWithText("오늘로 이동").performScrollTo().performClick()
        compose.onNodeWithText("검증석식 (1.2)").performScrollTo().assertIsDisplayed()
        repository.theme(ThemeMode.DARK)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("오늘은 무슨 메뉴?"))
        compose.onNode(hasText("석식") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("검증석식 (1.2)").performScrollTo().assertIsDisplayed()
        screenshot("meal-04-recreated-dark")
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithText("연결 관리").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("나이스 인증키 설정").assertDoesNotExist()
        compose.onNodeWithText("나이스 인증키 변경").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}

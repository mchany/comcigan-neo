package io.github.mchany.comciganneo.model

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.*

class WidgetModelTest {
    private val date = LocalDate.of(2026, 10, 28)
    @Test fun onlyExistingMealsForTheDisplayedDateAppear() {
        val school = NeisSchoolRef("G10", "1", "검증학교", "교육청", "주소")
        fun entry(day: LocalDate, code: String, menu: List<String>) = MealEntry(day.toString(), code, MealType.fromCode(code).label, "", menu)
        val snapshot = MealSnapshot(school, weekStart(date).toString(), 0, listOf(
            entry(date, "3", listOf("저녁밥")), entry(date, "1", listOf(" ")),
            entry(date.plusDays(1), "1", listOf("내일아침")), entry(date, "2", listOf("점심밥")), entry(date, "9", listOf("별식"))))
        assertEquals(listOf("2", "3", "9"), widgetMeals(snapshot, date).map { it.typeCode })
        assertTrue(widgetMeals(null, date).isEmpty())
    }
    @Test fun focusUsesProvidedStartsWithoutInventingEnds() {
        val slots = listOf(LessonSlot(date.toString(), 1, "08:10", Lesson("국어", "김")),
            LessonSlot(date.toString(), 2, "09:10", Lesson("영어", "이")))
        assertEquals(1, widgetFocus(slots, LocalTime.of(7, 0)).lesson?.period)
        assertEquals(2, widgetFocus(slots, LocalTime.of(8, 10)).lesson?.period)
        val afterLast = widgetFocus(slots, LocalTime.of(10, 0))
        assertEquals("마지막 시작 수업", afterLast.label)
        assertEquals(2, afterLast.lesson?.period)
        assertNull(afterLast.following)
        assertNull(widgetFocus(emptyList(), LocalTime.NOON).lesson)
    }
    @Test fun missingTimesAndCancelledLessonsDoNotFabricateProgress() {
        val selection = ClassSelection(School(1, "검증학교", "대전"), 1, 1)
        val snapshot = TimetableSnapshot(selection, weekStart(date).toString(), fetchedAt = 0, classCounts = listOf(1), slots = listOf(
            LessonSlot(date.toString(), 2, "잘못된 시각", Lesson("수학", "김")),
            LessonSlot(date.toString(), 1, original = Lesson("취소", "이")),
            LessonSlot(date.plusDays(1).toString(), 3, current = Lesson("내일", "박"))))
        val slots = widgetLessons(snapshot, date)
        assertEquals(listOf(2), slots.map { it.period })
        assertEquals("오늘의 수업", widgetFocus(slots, LocalTime.NOON).label)
    }
    @Test fun layoutAdaptsToWideTallAndLargeFonts() {
        assertFalse(wideWidget(170, 180, 1f))
        assertTrue(wideWidget(360, 180, 1f))
        assertFalse(wideWidget(300, 400, 1f))
        assertFalse(wideWidget(360, 180, 2f))
    }
}

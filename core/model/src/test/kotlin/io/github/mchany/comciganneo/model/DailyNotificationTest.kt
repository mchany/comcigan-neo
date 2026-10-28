package io.github.mchany.comciganneo.model

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.*

class DailyNotificationTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val selection = ClassSelection(School(1, "검증고", "대전"), 2, 1)

    @Test fun timeBoundaryAndKoreanTimezone() {
        val now = ZonedDateTime.parse("2026-10-27T22:30:00Z")
        assertEquals("2026-10-29T07:30+09:00[Asia/Seoul]", nextDailyNotification(450, now).toString())
        assertEquals(today, nextDailyNotification(451, now).toLocalDate())
        assertEquals(today.plusDays(1), nextDailyNotification(0, now).toLocalDate())
        assertEquals("2027-01-01T00:00+09:00[Asia/Seoul]", nextDailyNotification(0, ZonedDateTime.parse("2026-12-31T23:59:00+09:00")).toString())
    }
    @Test fun settingsValidateAndRemainIndependent() {
        assertFailsWith<IllegalArgumentException> { DailyNotificationSettings(minutes = 1440) }
        assertFailsWith<IllegalArgumentException> { nextDailyNotification(-1, ZonedDateTime.now()) }
        val preferences = AppPreferences(timetableNotification = DailyNotificationSettings(true, 360))
        assertTrue(preferences.notification(NotificationKind.TIMETABLE).enabled)
        assertFalse(preferences.notification(NotificationKind.MEALS).enabled)
        assertEquals("06:00", preferences.timetableNotification.timeLabel)
        assertEquals("11:30", preferences.mealNotification.timeLabel)
    }
    @Test fun timetableListsCurrentLessonsInOrderAndHonorsTeacherSetting() {
        val snapshot = TimetableSnapshot(selection, weekStart(today).toString(), fetchedAt = 0, classCounts = listOf(1), slots = listOf(
            LessonSlot(today.toString(), 3, "10:00", Lesson("영어", "김선생")),
            LessonSlot(today.toString(), 1, "08:00", Lesson("수학", "이선생"), Lesson("국어", "박선생"), true),
            LessonSlot(today.toString(), 2, original = Lesson("취소수업", "선생님"), changed = true),
            LessonSlot(today.plusDays(1).toString(), 1, current = Lesson("내일수업", "선생님")),
        ))
        val text = timetableNotificationText(snapshot, today, true)
        assertEquals("오늘의 시간표에요", text.title)
        assertEquals("1교시 08:00 수학 이선생\n3교시 10:00 영어 김선생", text.body)
        assertFalse(timetableNotificationText(snapshot, today, false).body.contains("선생"))
        assertContains(timetableNotificationText(snapshot, today, true, cached = true).body, "저장된 자료")
    }
    @Test fun deliveryRequiresFinishedSetupAndItsOwnSchoolAndSwitch() {
        val preferences = AppPreferences(selection = selection, timetableNotification = DailyNotificationSettings(true, 450), mealNotification = DailyNotificationSettings(true, 690))
        assertTrue(isNotificationReady(preferences, NotificationKind.TIMETABLE))
        assertFalse(isNotificationReady(preferences.copy(selection = null), NotificationKind.TIMETABLE))
        assertFalse(isNotificationReady(preferences.copy(notificationSetupPending = true), NotificationKind.TIMETABLE))
        assertFalse(isNotificationReady(preferences.copy(initialSetupPending = true), NotificationKind.TIMETABLE))
        assertFalse(isNotificationReady(preferences, NotificationKind.MEALS))
        val school = NeisSchoolRef("G10", "1", "검증고", "검증청", "주소")
        val connected = preferences.copy(mealKeyConfigured = true, mealSchools = mapOf("1" to school))
        assertTrue(isNotificationReady(connected, NotificationKind.MEALS))
        assertFalse(isNotificationReady(connected.copy(mealNotification = DailyNotificationSettings(false, 690)), NotificationKind.MEALS))
    }
    @Test fun mealsOnlyIncludeExistingMenusForTodayInMealOrder() {
        val school = NeisSchoolRef("G10", "1", "검증고", "검증청", "주소")
        fun meal(date: LocalDate, type: MealType, menu: List<String>) = MealEntry(date.toString(), type.code, type.label, "", menu)
        val snapshot = MealSnapshot(school, weekStart(today).toString(), 0, listOf(
            meal(today, MealType.DINNER, listOf("저녁밥", "국")),
            meal(today, MealType.BREAKFAST, emptyList()),
            meal(today.plusDays(1), MealType.LUNCH, listOf("내일밥")),
            meal(today, MealType.LUNCH, listOf("밥 (5.6)", "반찬")),
        ))
        val text = mealNotificationText(snapshot, today)
        assertEquals("오늘의 급식이에요", text.title)
        assertEquals("중식\n밥 (5.6)\n반찬\n\n석식\n저녁밥\n국", text.body)
        assertContains(mealNotificationText(snapshot, today.minusDays(1)).body, "등록된 급식 정보가 없어요")
    }
}

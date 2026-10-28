package io.github.mchany.comciganneo.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

val SchoolZone: ZoneId = ZoneId.of("Asia/Seoul")
fun schoolToday(): LocalDate = LocalDate.now(SchoolZone)
fun weekStart(date: LocalDate = schoolToday(), offset: Int = 0): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())

@Serializable
data class School(val code: Int, val name: String, val region: String)

@Serializable
data class ClassSelection(val school: School, val grade: Int, val classNumber: Int) {
    init { require(grade > 0 && classNumber > 0) }
    val label: String get() = "${grade}학년 ${classNumber}반"
}

@Serializable
data class Lesson(val subject: String, val teacher: String)

@Serializable
data class LessonSlot(
    val date: String,
    val period: Int,
    val startTime: String? = null,
    val current: Lesson? = null,
    val original: Lesson? = null,
    val changed: Boolean = false,
)

@Serializable
data class TimetableSnapshot(
    val selection: ClassSelection,
    val weekStart: String,
    val serverUpdatedAt: String? = null,
    val fetchedAt: Long,
    val classCounts: List<Int>,
    val slots: List<LessonSlot>,
    val parserVersion: Int = 1,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppPreferences(
    val selection: ClassSelection? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showTeachers: Boolean = true,
    val mealSchools: Map<String, NeisSchoolRef> = emptyMap(),
    val mealKeyConfigured: Boolean = false,
    val initialSetupPending: Boolean = false,
    val notificationSetupPending: Boolean = false,
    val timetableNotification: DailyNotificationSettings = DailyNotificationSettings(minutes = 7 * 60 + 30),
    val mealNotification: DailyNotificationSettings = DailyNotificationSettings(minutes = 11 * 60 + 30),
    val widgetPalette: WidgetPalette = WidgetPalette.APP,
)

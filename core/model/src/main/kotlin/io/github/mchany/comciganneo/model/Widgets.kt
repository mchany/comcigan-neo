package io.github.mchany.comciganneo.model

import java.time.LocalDate
import java.time.LocalTime

enum class WidgetPalette(val label: String) {
    APP("앱과 동일"), LAVENDER("라벤더"), ROSE("로즈"), MINT("민트"), PEACH("피치"), BLUE("블루");
}
enum class WidgetKind(val key: String, val label: String) {
    TIMETABLE("timetable", "오늘의 시간표"), MEALS("meals", "오늘의 급식");
}
data class WidgetFocus(val label: String, val lesson: LessonSlot?, val following: LessonSlot?)

fun widgetLessons(snapshot: TimetableSnapshot?, date: LocalDate): List<LessonSlot> =
    snapshot?.slots.orEmpty().filter { it.date == date.toString() && it.current != null }.sortedBy { it.period }

fun widgetMeals(snapshot: MealSnapshot?, date: LocalDate): List<MealEntry> =
    snapshot?.forDate(date).orEmpty().filter { meal -> meal.menu.any { it.isNotBlank() } }
        .sortedWith(compareBy({ it.typeCode.toIntOrNull() ?: Int.MAX_VALUE }, { it.typeName }))

fun widgetFocus(lessons: List<LessonSlot>, now: LocalTime): WidgetFocus {
    val sorted = lessons.sortedBy { it.period }
    val timed = sorted.mapNotNull { slot -> slot.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { slot to it } }
    val next = timed.firstOrNull { it.second > now }?.first
    val latest = timed.lastOrNull { it.second <= now }?.first
    val focus = next ?: latest ?: sorted.firstOrNull()
    return WidgetFocus(when { next != null -> "다음 수업"; latest != null -> "마지막 시작 수업"; else -> "오늘의 수업" },
        focus, next?.let { selected -> sorted.firstOrNull { it.period > selected.period } })
}

fun wideWidget(width: Int, height: Int, fontScale: Float): Boolean = width >= 280 && width > height * 1.15 && fontScale <= 1.4f

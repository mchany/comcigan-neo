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

/**
 * 역할: 지정한 날짜의 위젯 시간표 항목을 반환한다.
 * 입력: snapshot: TimetableSnapshot?, date: LocalDate.
 * 출력: List<LessonSlot> 값.
 */
fun widgetLessons(snapshot: TimetableSnapshot?, date: LocalDate): List<LessonSlot> =
    snapshot?.slots.orEmpty().filter { it.date == date.toString() && it.current != null }.sortedBy { it.period }

/**
 * 역할: 지정한 날짜의 위젯 급식 항목을 반환한다.
 * 입력: snapshot: MealSnapshot?, date: LocalDate.
 * 출력: List<MealEntry> 값.
 */
fun widgetMeals(snapshot: MealSnapshot?, date: LocalDate): List<MealEntry> =
    snapshot?.forDate(date).orEmpty().filter { meal -> meal.menu.any { it.isNotBlank() } }
        .sortedWith(compareBy({ it.typeCode.toIntOrNull() ?: Int.MAX_VALUE }, { it.typeName }))

/**
 * 역할: 현재 시각을 기준으로 표시할 수업 상태를 계산한다.
 * 입력: lessons: List<LessonSlot>, now: LocalTime.
 * 출력: WidgetFocus 값.
 */
fun widgetFocus(lessons: List<LessonSlot>, now: LocalTime): WidgetFocus {
    val sorted = lessons.sortedBy { it.period }
    val timed = sorted.mapNotNull { slot -> slot.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { slot to it } }
    val next = timed.firstOrNull { it.second > now }?.first
    val latest = timed.lastOrNull { it.second <= now }?.first
    val focus = next ?: latest ?: sorted.firstOrNull()
    return WidgetFocus(when { next != null -> "다음 수업"; latest != null -> "마지막 시작 수업"; else -> "오늘의 수업" },
        focus, next?.let { selected -> sorted.firstOrNull { it.period > selected.period } })
}

/**
 * 역할: 위젯 크기와 글꼴 배율이 가로형 조건을 만족하는지 확인한다.
 * 입력: width: Int, height: Int, fontScale: Float.
 * 출력: Boolean 값.
 */
fun wideWidget(width: Int, height: Int, fontScale: Float): Boolean = width >= 280 && width > height * 1.15 && fontScale <= 1.4f

package io.github.mchany.comciganneo.model

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter

enum class NotificationKind(val key: String, val label: String, val defaultMinutes: Int) {
    TIMETABLE("timetable", "시간표 알림", 450), MEALS("meals", "급식 알림", 690);
}

data class DailyNotificationSettings(val enabled: Boolean = false, val minutes: Int) {
    init { require(minutes in 0..1439) }
    val timeLabel: String get() = "%02d:%02d".format(minutes / 60, minutes % 60)
}

fun AppPreferences.notification(kind: NotificationKind) = when (kind) {
    NotificationKind.TIMETABLE -> timetableNotification
    NotificationKind.MEALS -> mealNotification
}

fun isNotificationReady(preferences: AppPreferences, kind: NotificationKind): Boolean {
    val selection = preferences.selection ?: return false
    return !preferences.initialSetupPending && !preferences.notificationSetupPending && preferences.notification(kind).enabled &&
        (kind != NotificationKind.MEALS || (preferences.mealKeyConfigured && preferences.mealSchools.containsKey(selection.school.code.toString())))
}

fun nextDailyNotification(minutes: Int, now: ZonedDateTime): ZonedDateTime {
    require(minutes in 0..1439)
    val koreaNow = now.withZoneSameInstant(SchoolZone)
    val today = koreaNow.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(SchoolZone)
    return if (today.isAfter(koreaNow)) today else today.plusDays(1)
}

data class DailyNotificationText(val title: String, val summary: String, val body: String)

private fun cachedNotice(fetchedAt: Long): String =
    "\n\n저장된 자료 · 마지막 조회 " + Instant.ofEpochMilli(fetchedAt).atZone(SchoolZone)
        .format(DateTimeFormatter.ofPattern("M.d HH:mm"))

fun timetableNotificationText(snapshot: TimetableSnapshot, date: LocalDate, showTeachers: Boolean, cached: Boolean = false): DailyNotificationText {
    val lessons = snapshot.slots.filter { it.date == date.toString() && it.current != null }.sortedBy { it.period }
    val body = lessons.joinToString("\n") { slot ->
        listOfNotNull("${slot.period}교시", slot.startTime, slot.current?.subject,
            slot.current?.teacher?.takeIf { showTeachers && it.isNotBlank() }).joinToString(" ")
    }.ifBlank { "오늘 등록된 수업이 없어요." }
    return DailyNotificationText("오늘의 시간표에요", "${snapshot.selection.school.name} · ${snapshot.selection.label} · 수업 ${lessons.size}개",
        body + if (cached) cachedNotice(snapshot.fetchedAt) else "")
}

fun mealNotificationText(snapshot: MealSnapshot, date: LocalDate, cached: Boolean = false): DailyNotificationText {
    val meals = snapshot.forDate(date).filter { entry -> entry.menu.any { it.isNotBlank() } }
        .sortedWith(compareBy({ it.typeCode.toIntOrNull() ?: Int.MAX_VALUE }, { it.typeName }))
    val body = meals.joinToString("\n\n") { entry ->
        entry.typeName.ifBlank { entry.type.label } + "\n" + entry.menu.filter { it.isNotBlank() }.joinToString("\n")
    }.ifBlank { "오늘 등록된 급식 정보가 없어요." }
    return DailyNotificationText("오늘의 급식이에요", "${snapshot.school.name} · 오늘 급식 ${meals.size}건",
        body + if (cached) cachedNotice(snapshot.fetchedAt) else "")
}

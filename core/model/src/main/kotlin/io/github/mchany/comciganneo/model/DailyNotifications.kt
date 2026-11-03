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

/**
 * 역할: 알림 종류에 해당하는 사용자 설정을 반환한다.
 * 입력: 알림 종류와 앱 환경 설정.
 * 출력: 해당 종류의 DailyNotificationSettings.
 */
fun AppPreferences.notification(kind: NotificationKind) = when (kind) {
    NotificationKind.TIMETABLE -> timetableNotification
    NotificationKind.MEALS -> mealNotification
}

/**
 * 역할: 초기 설정 완료 여부와 종류별 필수 정보가 준비됐는지 확인한다.
 * 입력: 앱 환경 설정과 확인할 알림 종류.
 * 출력: Boolean 값.
 */
fun isNotificationReady(preferences: AppPreferences, kind: NotificationKind): Boolean {
    val selection = preferences.selection ?: return false
    return !preferences.initialSetupPending && !preferences.notificationSetupPending && preferences.notification(kind).enabled &&
        (kind != NotificationKind.MEALS || (preferences.mealKeyConfigured && preferences.mealSchools.containsKey(selection.school.code.toString())))
}

/**
 * 역할: 한국 시간대에서 다음 일일 알림 시각을 계산한다.
 * 입력: 하루 중 분 단위 알림 시각과 현재 시각.
 * 출력: ZonedDateTime 값.
 */
fun nextDailyNotification(minutes: Int, now: ZonedDateTime): ZonedDateTime {
    require(minutes in 0..1439)
    val koreaNow = now.withZoneSameInstant(SchoolZone)
    val today = koreaNow.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(SchoolZone)
    return if (today.isAfter(koreaNow)) today else today.plusDays(1)
}

/**
 * 역할: 날짜와 알림 시각으로 중복 발송 확인용 식별자를 만든다.
 * 입력: 알림 날짜와 분 단위 시각.
 * 출력: String 값.
 */
fun notificationDeliverySlot(date: LocalDate, minutes: Int): String {
    require(minutes in 0..1439)
    return "$date|$minutes"
}

/**
 * 역할: 오늘 발송 시각을 기록하고 이전 날짜 기록을 정리한다.
 * 입력: 기존 발송 기록, 날짜, 분 단위 알림 시각.
 * 출력: Set<String> 값.
 */
fun recordNotificationDelivery(existing: Set<String>, date: LocalDate, minutes: Int): Set<String> =
    existing.filterTo(mutableSetOf()) { it.startsWith("$date|") } + notificationDeliverySlot(date, minutes)

data class DailyNotificationText(val title: String, val summary: String, val body: String)

/**
 * 역할: 저장 자료의 마지막 조회 시각을 안내하는 문구를 만든다.
 * 입력: 자료를 조회한 시각의 epoch 밀리초 값.
 * 출력: String 값.
 */
private fun cachedNotice(fetchedAt: Long): String =
    "\n\n저장된 자료 · 마지막 조회 " + Instant.ofEpochMilli(fetchedAt).atZone(SchoolZone)
        .format(DateTimeFormatter.ofPattern("M.d HH:mm"))

/**
 * 역할: 해당 날짜의 수업과 교사 표시 설정으로 시간표 알림 문구를 만든다.
 * 입력: 시간표 스냅샷, 날짜, 교사 표시 여부, 저장 자료 사용 여부.
 * 출력: DailyNotificationText 값.
 */
fun timetableNotificationText(snapshot: TimetableSnapshot, date: LocalDate, showTeachers: Boolean, cached: Boolean = false): DailyNotificationText {
    val lessons = snapshot.slots.filter { it.date == date.toString() && it.current != null }.sortedBy { it.period }
    val body = lessons.joinToString("\n") { slot ->
        listOfNotNull("${slot.period}교시", slot.startTime, slot.current?.subject,
            slot.current?.teacher?.takeIf { showTeachers && it.isNotBlank() }).joinToString(" ")
    }.ifBlank { "오늘 등록된 수업이 없어요." }
    return DailyNotificationText("오늘의 시간표에요", "${snapshot.selection.school.name} · ${snapshot.selection.label} · 수업 ${lessons.size}개",
        body + if (cached) cachedNotice(snapshot.fetchedAt) else "")
}

/**
 * 역할: 해당 날짜의 메뉴를 식사 순으로 정렬해 급식 알림 문구를 만든다.
 * 입력: 급식 스냅샷, 날짜, 저장 자료 사용 여부.
 * 출력: DailyNotificationText 값.
 */
fun mealNotificationText(snapshot: MealSnapshot, date: LocalDate, cached: Boolean = false): DailyNotificationText {
    val meals = snapshot.forDate(date).filter { entry -> entry.menu.any { it.isNotBlank() } }
        .sortedWith(compareBy({ it.typeCode.toIntOrNull() ?: Int.MAX_VALUE }, { it.typeName }))
    val body = meals.joinToString("\n\n") { entry ->
        entry.typeName.ifBlank { entry.type.label } + "\n" + entry.menu.filter { it.isNotBlank() }.joinToString("\n")
    }.ifBlank { "오늘 등록된 급식 정보가 없어요." }
    return DailyNotificationText("오늘의 급식이에요", "${snapshot.school.name} · 오늘 급식 ${meals.size}건",
        body + if (cached) cachedNotice(snapshot.fetchedAt) else "")
}

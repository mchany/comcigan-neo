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
 * 알림 종류에 해당하는 사용자 설정을 선택한다.
 * 입력: 알림 종류와 앱 환경 설정.
 * 출력: 시간표 또는 급식 알림 설정을 반환한다.
 */
fun AppPreferences.notification(kind: NotificationKind) = when (kind) {
    NotificationKind.TIMETABLE -> timetableNotification
    NotificationKind.MEALS -> mealNotification
}

/**
 * 알림 발송에 필요한 사용자 설정이 준비됐는지 확인한다.
 * 입력: 앱 환경 설정과 확인할 알림 종류.
 * 출력: 설정이 모두 준비됐으면 true, 아니면 false를 반환한다.
 */
fun isNotificationReady(preferences: AppPreferences, kind: NotificationKind): Boolean {
    val selection = preferences.selection ?: return false
    return !preferences.initialSetupPending && !preferences.notificationSetupPending && preferences.notification(kind).enabled &&
        (kind != NotificationKind.MEALS || (preferences.mealKeyConfigured && preferences.mealSchools.containsKey(selection.school.code.toString())))
}

/**
 * 설정 시각을 기준으로 다음 알림 예정 시각을 계산한다.
 * 입력: 하루 중 분 단위 알림 시각과 현재 시각.
 * 출력: 한국 시간대의 다음 알림 시각을 반환한다.
 */
fun nextDailyNotification(minutes: Int, now: ZonedDateTime): ZonedDateTime {
    require(minutes in 0..1439)
    val koreaNow = now.withZoneSameInstant(SchoolZone)
    val today = koreaNow.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(SchoolZone)
    return if (today.isAfter(koreaNow)) today else today.plusDays(1)
}

/**
 * 날짜와 분 단위 시각을 중복 확인용 식별자로 만든다.
 * 입력: 알림 날짜와 분 단위 시각.
 * 출력: 날짜와 시각을 결합한 문자열을 반환한다.
 */
fun notificationDeliverySlot(date: LocalDate, minutes: Int): String {
    require(minutes in 0..1439)
    return "$date|$minutes"
}

/**
 * 오늘의 발송 기록을 저장하고 이전 날짜의 기록을 정리한다.
 * 입력: 기존 발송 기록, 날짜, 분 단위 알림 시각.
 * 출력: 오늘 기록만 남기고 현재 발송 슬롯을 포함한 집합을 반환한다.
 */
fun recordNotificationDelivery(existing: Set<String>, date: LocalDate, minutes: Int): Set<String> =
    existing.filterTo(mutableSetOf()) { it.startsWith("$date|") } + notificationDeliverySlot(date, minutes)

data class DailyNotificationText(val title: String, val summary: String, val body: String)

/**
 * 저장된 자료의 마지막 조회 시각을 알림에 표시할 문구로 만든다.
 * 입력: 자료를 조회한 시각의 epoch 밀리초 값.
 * 출력: 한국 시간 기준 조회 시각을 포함한 안내 문구를 반환한다.
 */
private fun cachedNotice(fetchedAt: Long): String =
    "\n\n저장된 자료 · 마지막 조회 " + Instant.ofEpochMilli(fetchedAt).atZone(SchoolZone)
        .format(DateTimeFormatter.ofPattern("M.d HH:mm"))

/**
 * 특정 날짜의 시간표 자료를 알림 제목, 요약, 본문으로 구성한다.
 * 입력: 시간표 스냅샷, 날짜, 교사 표시 여부, 저장 자료 사용 여부.
 * 출력: 시간표 알림에 사용할 텍스트 묶음을 반환한다.
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
 * 특정 날짜의 급식 자료를 알림 제목, 요약, 본문으로 구성한다.
 * 입력: 급식 스냅샷, 날짜, 저장 자료 사용 여부.
 * 출력: 급식 알림에 사용할 텍스트 묶음을 반환한다.
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

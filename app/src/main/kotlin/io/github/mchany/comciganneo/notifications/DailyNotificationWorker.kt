package io.github.mchany.comciganneo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DailyNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /**
     * 역할: 발송 조건과 설정을 재확인하고 해당 날짜에 실제 수업이나 메뉴가 있을 때만 알림을 게시한다.
     * 입력: WorkManager가 전달한 알림 종류, 날짜, 설정 시각과 작업 실행 정보.
     * 출력: Result 값.
     */
    override suspend fun doWork(): Result {
        val kind = NotificationKind.entries.firstOrNull { it.name == inputData.getString("kind") } ?: return Result.failure()
        val date = schoolToday()
        if (inputData.getString("date") != date.toString()) return Result.success()
        val repository = (applicationContext as NeoApplication).repository
        val preferences = repository.preferences.first()
        val minutes = inputData.getInt("minutes", -1)
        if (!isNotificationReady(preferences, kind) || preferences.notification(kind).minutes != minutes ||
            !DailyNotificationPublisher.allowed(applicationContext, kind) || repository.notificationDelivered(kind, date, minutes)) return Result.success()
        val selection = preferences.selection!!
        val school = preferences.mealSchool
        try {
            var fallback = false
            val timetable = if (kind == NotificationKind.TIMETABLE) {
                val cache = repository.cached(selection, 0)
                val fresh = cache != null && System.currentTimeMillis() - cache.fetchedAt in 0L..300_000L
                if (fresh) cache else fetch { repository.refresh(selection, 0) }.also { if (it == null) fallback = true } ?: cache
            } else null
            val meals = if (kind == NotificationKind.MEALS && school != null) {
                val cache = repository.cachedMeals(school, date)
                if (cache?.isFresh(date) == true) cache else fetch { repository.refreshMeals(school, date) }.also { if (it == null) fallback = true } ?: cache
            } else null
            currentCoroutineContext().ensureActive()
            val current = repository.preferences.first()
            if (schoolToday() != date || !isNotificationReady(current, kind) || current.notification(kind).minutes != minutes ||
                current.selection != selection || (kind == NotificationKind.MEALS && current.mealSchool != school)) return Result.success()
            val hasContent = when (kind) {
                NotificationKind.TIMETABLE -> timetable?.slots?.any { it.date == date.toString() && it.current != null } == true
                NotificationKind.MEALS -> meals?.forDate(date)?.any { entry -> entry.menu.any(String::isNotBlank) } == true
            }
            if ((kind == NotificationKind.TIMETABLE && timetable != null || kind == NotificationKind.MEALS && meals != null) && !hasContent) {
                return Result.success()
            }
            val text = when {
                timetable != null -> timetableNotificationText(timetable, date, current.showTeachers, fallback)
                meals != null -> mealNotificationText(meals, date, fallback)
                else -> DailyNotificationText(if (kind == NotificationKind.TIMETABLE) "오늘의 시간표에요" else "오늘의 급식이에요",
                    if (kind == NotificationKind.TIMETABLE) selection.school.name else school?.name.orEmpty(),
                    "자료를 가져오지 못했어요. 인터넷 연결을 확인하고 앱에서 다시 조회해 주세요.")
            }
            if (repository.notificationDelivered(kind, date, minutes)) return Result.success()
            if (DailyNotificationPublisher.post(applicationContext, kind, text, date)) repository.markNotificationDelivered(kind, date, minutes)
            return Result.success()
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { return if (runAttemptCount < 2 && schoolToday() == date) Result.retry() else Result.failure() }
    }

    /**
     * 역할: 제한 시간 내 자료를 조회하고 취소 외의 조회 오류를 null로 처리한다.
     * 입력: 실행할 일시 중단 자료 조회 함수.
     * 출력: T? 값.
     */
    private suspend fun <T> fetch(block: suspend () -> T): T? = withTimeoutOrNull(45_000) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}

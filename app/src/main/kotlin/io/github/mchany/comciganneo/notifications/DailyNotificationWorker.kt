package io.github.mchany.comciganneo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DailyNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val kind = NotificationKind.entries.firstOrNull { it.name == inputData.getString("kind") } ?: return Result.failure()
        val date = schoolToday()
        if (inputData.getString("date") != date.toString()) return Result.success()
        val repository = (applicationContext as NeoApplication).repository
        val preferences = repository.preferences.first()
        val minutes = inputData.getInt("minutes", -1)
        if (!isNotificationReady(preferences, kind) || preferences.notification(kind).minutes != minutes ||
            !DailyNotificationPublisher.allowed(applicationContext, kind) || repository.notificationDelivered(kind, date)) return Result.success()
        val selection = preferences.selection!!
        val school = preferences.mealSchools[selection.school.code.toString()]
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
                current.selection != selection || (kind == NotificationKind.MEALS && current.mealSchools[selection.school.code.toString()] != school)) return Result.success()
            val text = when {
                timetable != null -> timetableNotificationText(timetable, date, current.showTeachers, fallback)
                meals != null -> mealNotificationText(meals, date, fallback)
                else -> DailyNotificationText(if (kind == NotificationKind.TIMETABLE) "오늘의 시간표에요" else "오늘의 급식이에요",
                    if (kind == NotificationKind.TIMETABLE) selection.school.name else school?.name.orEmpty(),
                    "자료를 가져오지 못했어요. 인터넷 연결을 확인하고 앱에서 다시 조회해 주세요.")
            }
            if (DailyNotificationPublisher.post(applicationContext, kind, text, date)) repository.markNotificationDelivered(kind, date)
            return Result.success()
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { return if (runAttemptCount < 2 && schoolToday() == date) Result.retry() else Result.failure() }
    }

    private suspend fun <T> fetch(block: suspend () -> T): T? = withTimeoutOrNull(45_000) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}

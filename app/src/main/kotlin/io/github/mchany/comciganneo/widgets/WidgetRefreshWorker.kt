package io.github.mchany.comciganneo.widgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /**
     * 역할: 위젯 자료 갱신 작업을 실행한다.
     * 입력: 없음.
     * 출력: Result 값.
     */
    override suspend fun doWork(): Result {
        val kinds = NeoWidgets.instances(applicationContext).map { it.second }.toSet()
        if (kinds.isEmpty()) { NeoWidgets.synchronize(applicationContext); return Result.success() }
        val repository = (applicationContext as NeoApplication).repository
        val preferences = repository.preferences.first()
        val selection = preferences.selection
        val force = inputData.getBoolean("force", false)
        WidgetRenderer.updateAll(applicationContext)
        if (selection != null) {
            if (WidgetKind.TIMETABLE in kinds) fetch {
                val cache = repository.cached(selection, 0)
                if (force || cache == null || System.currentTimeMillis() - cache.fetchedAt !in 0L..300_000L) repository.refresh(selection, 0)
            }
            currentCoroutineContext().ensureActive()
            preferences.mealSchool?.let { school ->
                if (WidgetKind.MEALS in kinds && preferences.mealKeyConfigured) fetch { repository.refreshMeals(school, schoolToday(), force) }
            }
        }
        currentCoroutineContext().ensureActive()
        WidgetRenderer.updateAll(applicationContext)
        return Result.success()
    }
    /**
     * 역할: 제한 시간 안에 외부 자료를 조회한다.
     * 입력: block: suspend () -> Unit.
     * 출력: 없음(Unit).
     */
    private suspend fun fetch(block: suspend () -> Unit) {
        withTimeoutOrNull(45_000) {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep dated cached data. */ }
        }
    }
}

package io.github.mchany.comciganneo.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.work.*
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object NeoWidgets {
    const val ACTION_REFRESH = "io.github.mchany.comciganneo.WIDGET_REFRESH"
    private const val ACTION_BOUNDARY = "io.github.mchany.comciganneo.WIDGET_BOUNDARY"
    val providers = listOf(TimetableWidgetProvider::class.java to WidgetKind.TIMETABLE,
        WideTimetableWidgetProvider::class.java to WidgetKind.TIMETABLE,
        MealsWidgetProvider::class.java to WidgetKind.MEALS, WideMealsWidgetProvider::class.java to WidgetKind.MEALS)

    fun instances(context: Context): List<Pair<Int, WidgetKind>> {
        val manager = AppWidgetManager.getInstance(context)
        return providers.flatMap { (provider, kind) -> manager.getAppWidgetIds(ComponentName(context, provider)).map { it to kind } }
    }
    fun requestRefresh(context: Context, force: Boolean = false) {
        if (instances(context).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork("neo-widget-refresh", if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(workDataOf("force" to force)).build())
    }
    fun synchronize(context: Context) {
        val work = WorkManager.getInstance(context)
        if (instances(context).isEmpty()) {
            work.cancelUniqueWork("neo-widget-periodic")
            work.cancelUniqueWork("neo-widget-refresh")
            val intent = PendingIntent.getBroadcast(context, 501, boundaryIntent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            intent?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
        } else {
            work.enqueueUniquePeriodicWork("neo-widget-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build())
        }
    }
    private fun boundaryIntent(context: Context) = Intent(context, WidgetUpdateReceiver::class.java).setAction(ACTION_BOUNDARY)
    fun scheduleBoundary(context: Context, data: WidgetData) {
        val midnight = data.date.plusDays(1).atStartOfDay(SchoolZone).plusSeconds(2)
        val lessons = if (instances(context).any { it.second == WidgetKind.TIMETABLE }) data.lessons else emptyList()
        val next = lessons.mapNotNull { slot -> slot.startTime?.let { runCatching { data.date.atTime(LocalTime.parse(it)).atZone(SchoolZone) }.getOrNull() } }
            .filter { it.isAfter(data.now) }.minOrNull() ?: midnight
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 501, boundaryIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExact(AlarmManager.RTC, next.toInstant().toEpochMilli(), pending)
            else alarm.set(AlarmManager.RTC, next.toInstant().toEpochMilli(), pending)
        } catch (_: SecurityException) { alarm.set(AlarmManager.RTC, next.toInstant().toEpochMilli(), pending) }
    }
    @Suppress("DEPRECATION") // Legacy wallpaper broadcasts complement the Android 12+ color listener.
    fun isUpdateAction(action: String?) = action in setOf(ACTION_REFRESH, ACTION_BOUNDARY, Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_WALLPAPER_CHANGED)
}

abstract class NeoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onDeleted(context: Context, ids: IntArray) { NeoWidgets.synchronize(context) }
    override fun onDisabled(context: Context) { NeoWidgets.synchronize(context) }
    private fun update(context: Context) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { NeoWidgets.synchronize(context); WidgetRenderer.updateAll(context); NeoWidgets.requestRefresh(context) } }
            catch (_: Exception) { /* A resize/removal may invalidate a host during delivery. */ }
            finally { result.finish() }
        }
    }
}
class TimetableWidgetProvider : NeoWidgetProvider()
class WideTimetableWidgetProvider : NeoWidgetProvider()
class MealsWidgetProvider : NeoWidgetProvider()
class WideMealsWidgetProvider : NeoWidgetProvider()

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NeoWidgets.isUpdateAction(intent.action)) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { NeoWidgets.synchronize(context); WidgetRenderer.updateAll(context); NeoWidgets.requestRefresh(context, intent.action == NeoWidgets.ACTION_REFRESH) } }
            catch (_: Exception) { /* Retry via the next host or periodic update. */ }
            finally { result.finish() }
        }
    }
}

class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
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
            preferences.mealSchools[selection.school.code.toString()]?.let { school ->
                if (WidgetKind.MEALS in kinds && preferences.mealKeyConfigured) fetch { repository.refreshMeals(school, schoolToday(), force) }
            }
        }
        currentCoroutineContext().ensureActive()
        WidgetRenderer.updateAll(applicationContext)
        return Result.success()
    }
    private suspend fun fetch(block: suspend () -> Unit) {
        withTimeoutOrNull(45_000) {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Keep dated cached data. */ }
        }
    }
}

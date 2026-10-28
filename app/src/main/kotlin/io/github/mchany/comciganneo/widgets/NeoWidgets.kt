package io.github.mchany.comciganneo.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import io.github.mchany.comciganneo.model.*
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

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

    /**
     * 역할: 등록된 위젯의 ID와 종류 목록을 가져온다.
     * 입력: context: Context.
     * 출력: List<Pair<Int, WidgetKind>> 값.
     */
    fun instances(context: Context): List<Pair<Int, WidgetKind>> {
        val manager = AppWidgetManager.getInstance(context)
        return providers.flatMap { (provider, kind) -> manager.getAppWidgetIds(ComponentName(context, provider)).map { it to kind } }
    }
    /**
     * 역할: 위젯 데이터 새로고침 작업을 요청한다.
     * 입력: context: Context, force: Boolean.
     * 출력: 없음(Unit).
     */
    fun requestRefresh(context: Context, force: Boolean = false) {
        if (instances(context).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork("neo-widget-refresh", if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(workDataOf("force" to force)).build())
    }
    /**
     * 역할: 위젯 인스턴스와 경계 시각 알람을 동기화한다.
     * 입력: context: Context.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 위젯 표시 경계 시각에 실행할 PendingIntent를 만든다.
     * 입력: context: Context.
     * 출력: Intent 값.
     */
    private fun boundaryIntent(context: Context) = Intent(context, WidgetUpdateReceiver::class.java).setAction(ACTION_BOUNDARY)
    /**
     * 역할: 위젯 자료의 날짜나 수업 변경 시각에 갱신을 예약한다.
     * 입력: context: Context, data: WidgetData.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 인텐트가 위젯 새로고침이나 시스템 복원 요청인지 확인한다.
     * 입력: action: String?.
     * 출력: Boolean 값.
     */
    fun isUpdateAction(action: String?) = action in setOf(ACTION_REFRESH, ACTION_BOUNDARY, Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_WALLPAPER_CHANGED)
}

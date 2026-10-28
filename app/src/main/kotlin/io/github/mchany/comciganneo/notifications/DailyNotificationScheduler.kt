package io.github.mchany.comciganneo.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class DailyNotificationScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactly() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun synchronize(preferences: AppPreferences) {
        for (kind in NotificationKind.entries) {
            val settings = preferences.notification(kind)
            if (!isNotificationReady(preferences, kind) || !DailyNotificationPublisher.allowed(context, kind)) {
                alarms.cancel(pending(kind))
                WorkManager.getInstance(context).cancelAllWorkByTag(kind.key)
                DailyNotificationPublisher.cancel(context, kind)
                continue
            }
            val next = nextDailyNotification(settings.minutes, ZonedDateTime.now(SchoolZone))
            val intent = pending(kind, next.toLocalDate().toString(), settings.minutes)
            try {
                if (canScheduleExactly()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
            } catch (_: SecurityException) {
                // Special access can be revoked between the check and the call.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), intent)
            }
        }
    }

    private fun pending(kind: NotificationKind, date: String = "", minutes: Int = -1): PendingIntent =
        PendingIntent.getBroadcast(context, kind.ordinal, Intent(context, DailyAlarmReceiver::class.java)
            .setAction("${context.packageName}.daily.${kind.key}")
            .putExtra("kind", kind.name).putExtra("date", date).putExtra("minutes", minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Alarms only enqueue durable work; network access never runs inside a broadcast. */
class DailyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestedKind = NotificationKind.entries.firstOrNull { intent.action == "${context.packageName}.daily.${it.key}" }
        if (requestedKind == null && intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val app = context.applicationContext as NeoApplication
                    val preferences = app.repository.preferences.first()
                    DailyNotificationScheduler(context).synchronize(preferences)
                    val kind = requestedKind ?: return@withTimeout
                    val minutes = intent.getIntExtra("minutes", -1)
                    val date = intent.getStringExtra("date") ?: return@withTimeout
                    if (date != schoolToday().toString() || !isNotificationReady(preferences, kind) || preferences.notification(kind).minutes != minutes) return@withTimeout
                    // Deliver both together when their times match, even under the idle alarm quota.
                    for (due in NotificationKind.entries.filter { isNotificationReady(preferences, it) && preferences.notification(it).minutes == minutes }) {
                        val builder = OneTimeWorkRequestBuilder<DailyNotificationWorker>()
                            .setInputData(workDataOf("kind" to due.name, "date" to date, "minutes" to minutes))
                            .addTag(due.key)
                        if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        WorkManager.getInstance(context).enqueueUniqueWork("daily-${due.key}-$date", ExistingWorkPolicy.KEEP, builder.build())
                            .result.get(3, TimeUnit.SECONDS)
                    }
                }
            } catch (_: Exception) {
                // System delivery must always finish, including shutdown and database cancellation.
            } finally { pendingResult.finish() }
        }
    }
}

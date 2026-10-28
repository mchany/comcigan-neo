package io.github.mchany.comciganneo.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.WorkManager
import io.github.mchany.comciganneo.model.*
import java.time.ZonedDateTime

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

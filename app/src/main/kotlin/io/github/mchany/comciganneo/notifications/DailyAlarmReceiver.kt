package io.github.mchany.comciganneo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import io.github.mchany.comciganneo.NeoApplication
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

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
                        WorkManager.getInstance(context).enqueueUniqueWork("daily-${due.key}-$date-$minutes", ExistingWorkPolicy.KEEP, builder.build())
                            .result.get(3, TimeUnit.SECONDS)
                    }
                }
            } catch (_: Exception) {
                // System delivery must always finish, including shutdown and database cancellation.
            } finally { pendingResult.finish() }
        }
    }
}

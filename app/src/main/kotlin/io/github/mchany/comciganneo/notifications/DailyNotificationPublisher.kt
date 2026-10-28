package io.github.mchany.comciganneo.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.mchany.comciganneo.MainActivity
import io.github.mchany.comciganneo.R
import io.github.mchany.comciganneo.model.*
import java.time.LocalDate

object DailyNotificationPublisher {
    fun channel(kind: NotificationKind) = "daily_${kind.key}"
    fun id(kind: NotificationKind) = 200 + kind.ordinal

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationKind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(channel(kind), kind.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (kind == NotificationKind.TIMETABLE) "설정한 시각에 오늘의 수업을 알려드려요" else "설정한 시각에 오늘 등록된 급식을 알려드려요"
            })
        }
    }

    fun allowed(context: Context, kind: NotificationKind? = null): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return kind == null || context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel(kind))?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission") // Permission and channel state are checked immediately before posting.
    fun post(context: Context, kind: NotificationKind, text: DailyNotificationText, date: LocalDate): Boolean {
        if (!allowed(context, kind)) return false
        val open = PendingIntent.getActivity(context, id(kind), Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra("notification_kind", kind.key)
            .putExtra("notification_event", "${kind.key}-$date")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel(kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title).setContentText(text.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body).setBigContentTitle(text.title).setSummaryText(text.summary))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSubText("${date.monthValue}.${date.dayOfMonth}")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(date.atStartOfDay(SchoolZone).toInstant().toEpochMilli()).setShowWhen(false)
            .build()
        return try { NotificationManagerCompat.from(context).notify(id(kind), notification); true }
        catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, kind: NotificationKind) = NotificationManagerCompat.from(context).cancel(id(kind))
}

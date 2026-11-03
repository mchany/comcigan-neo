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
    /**
     * 알림 종류의 Android 알림 채널 식별자를 만든다.
     * 입력: 알림 종류.
     * 출력: 채널 ID 문자열을 반환한다.
     */
    fun channel(kind: NotificationKind) = "daily_${kind.key}"

    /**
     * 알림 종류의 Android 알림 ID를 계산한다.
     * 입력: 알림 종류.
     * 출력: 알림 관리자에서 사용할 정수 ID를 반환한다.
     */
    fun id(kind: NotificationKind) = 200 + kind.ordinal

    /**
     * 앱에서 사용하는 알림 채널을 생성하거나 갱신한다.
     * 입력: 알림 채널을 등록할 Android 컨텍스트.
     * 출력: 채널 생성 작업을 수행하고 반환값은 없다.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationKind.entries.forEach { kind ->
            manager.createNotificationChannel(NotificationChannel(channel(kind), kind.label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (kind == NotificationKind.TIMETABLE) "설정한 시각에 오늘의 수업을 알려드려요" else "설정한 시각에 오늘 등록된 급식을 알려드려요"
            })
        }
    }

    /**
     * 시스템과 선택 채널에서 알림 게시가 허용되는지 확인한다.
     * 입력: Android 컨텍스트와 선택적으로 확인할 알림 종류.
     * 출력: 게시가 허용되면 true, 아니면 false를 반환한다.
     */
    fun allowed(context: Context, kind: NotificationKind? = null): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return kind == null || context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel(kind))?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 알림 내용을 Android 알림으로 게시한다.
     * 입력: Android 컨텍스트, 알림 종류, 표시할 텍스트, 자료 날짜.
     * 출력: 시스템에 알림을 게시했으면 true, 권한이나 게시 오류가 있으면 false를 반환한다.
     */
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

    /**
     * 지정한 종류의 현재 알림을 취소한다.
     * 입력: Android 컨텍스트와 취소할 알림 종류.
     * 출력: 알림 관리자에서 알림을 취소하며 반환값은 없다.
     */
    fun cancel(context: Context, kind: NotificationKind) = NotificationManagerCompat.from(context).cancel(id(kind))
}

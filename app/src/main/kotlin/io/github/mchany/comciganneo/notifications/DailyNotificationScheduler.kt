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

    /**
     * 역할: Android 버전과 사용자 권한에 따라 정확한 알람 예약 가능 여부를 확인한다.
     * 입력: 생성 시 전달된 Android 컨텍스트와 기기 OS 상태.
     * 출력: Boolean 값.
     */
    fun canScheduleExactly() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    /**
     * 역할: 사용자 설정과 게시 권한을 기준으로 일일 알람을 예약하거나 기존 작업을 취소한다.
     * 입력: 현재 앱의 알림 설정.
     * 출력: 없음(Unit).
     */
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

    /**
     * 역할: 날짜와 시각을 담아 알림 수신기로 전달할 PendingIntent를 만든다.
     * 입력: 알림 종류, 선택적 날짜 문자열, 분 단위 알림 시각.
     * 출력: PendingIntent 값.
     */
    private fun pending(kind: NotificationKind, date: String = "", minutes: Int = -1): PendingIntent =
        PendingIntent.getBroadcast(context, kind.ordinal, Intent(context, DailyAlarmReceiver::class.java)
            .setAction("${context.packageName}.daily.${kind.key}")
            .putExtra("kind", kind.name).putExtra("date", date).putExtra("minutes", minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

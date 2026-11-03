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
     * 기기에서 정확한 알람을 예약할 수 있는지 확인한다.
     * 입력: 생성 시 전달된 Android 컨텍스트와 기기 OS 상태.
     * 출력: 정확한 알람 예약이 가능하면 true를 반환한다.
     */
    fun canScheduleExactly() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    /**
     * 알림 설정을 기준으로 알람 예약과 기존 알림 상태를 동기화한다.
     * 입력: 현재 앱의 알림 설정.
     * 출력: 필요한 알람을 예약하거나 비활성 알림의 예약 및 게시물을 취소한다.
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
     * 알림 종류와 실행 시각을 담은 브로드캐스트 PendingIntent를 만든다.
     * 입력: 알림 종류, 선택적 날짜 문자열, 분 단위 알림 시각.
     * 출력: 알람 관리자에 등록할 PendingIntent를 반환한다.
     */
    private fun pending(kind: NotificationKind, date: String = "", minutes: Int = -1): PendingIntent =
        PendingIntent.getBroadcast(context, kind.ordinal, Intent(context, DailyAlarmReceiver::class.java)
            .setAction("${context.packageName}.daily.${kind.key}")
            .putExtra("kind", kind.name).putExtra("date", date).putExtra("minutes", minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

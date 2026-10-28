package io.github.mchany.comciganneo

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.data.*
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.notifications.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/** Start with cleared .smoke data and exact-alarm access; POST_NOTIFICATIONS starts denied. */
@RunWith(AndroidJUnit4::class)
class NotificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun eventually(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!check()) {
            if (SystemClock.elapsedRealtime() >= deadline) error("Timed out waiting for notification state")
            SystemClock.sleep(100)
        }
    }

    @Test fun permissionTimesBackgroundDeliveryAndTapDestinations() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".smoke"))
        val repository = (context.applicationContext as NeoApplication).repository
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val today = schoolToday()
        val selection = ClassSelection(School(424242, "알림검증고등학교", "대전"), 2, 1)
        val school = NeisSchoolRef("G10", "fixture", "급식알림검증고등학교", "검증교육청", "검증 주소")
        val now = System.currentTimeMillis()
        val timetable = TimetableSnapshot(selection, weekStart(today).toString(), fetchedAt = now, classCounts = listOf(1, 1), slots = listOf(
            LessonSlot(today.toString(), 2, "09:10", Lesson("알림영어", "김선생")),
            LessonSlot(today.toString(), 1, "08:10", Lesson("알림수학", "이선생")),
        ))
        val meals = MealSnapshot(school, weekStart(today).toString(), now, listOf(
            MealEntry(today.toString(), "3", "석식", "", listOf("검증저녁밥", "검증저녁국")),
            MealEntry(today.toString(), "2", "중식", "", listOf("검증점심밥", "검증점심국")),
        ))
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, "timetables.db").addMigrations(Migration1To2).build()
        try {
            database.snapshots().save(SnapshotEntity(selection.school.code, 2, 1, timetable.weekStart, Json.encodeToString(timetable)))
            database.meals().save(MealSnapshotEntity(school.officeCode, school.schoolCode, meals.weekStart, Json.encodeToString(meals)))
        } finally { database.close() }
        repository.select(selection)
        repository.setMealSchool(selection.school.code, school)
        repository.finishInitialSetup()
        repository.notification(NotificationKind.TIMETABLE, DailyNotificationSettings(true, 450))
        repository.notification(NotificationKind.MEALS, DailyNotificationSettings(true, 690))
        waitFor(hasText("오늘"))
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithContentDescription("시간표 알림").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("급식 알림").performScrollTo().assertIsOn()
        compose.onNodeWithText("알림 권한이 꺼져 있어요. 시스템 설정에서 허용해 주세요.").performScrollTo().assertIsDisplayed()
        assertFalse(DailyNotificationPublisher.allowed(context))
        assertTrue(manager.activeNotifications.isEmpty())

        compose.onNodeWithTag("notification-time-timetable").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("06")
        compose.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("45")
        compose.onNodeWithText("저장").performClick()
        eventually { runBlocking { repository.preferences.first().timetableNotification.minutes == 405 } }
        assertEquals(690, repository.preferences.first().mealNotification.minutes)

        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설정"))
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithTag("notification-time-timetable").performScrollTo().assertTextContains("알림 시각 · 06:45")
        compose.onNodeWithContentDescription("급식 알림").performScrollTo().assertIsOn()

        val minutes = ZonedDateTime.now(SchoolZone).let { it.hour * 60 + it.minute }
        repository.notification(NotificationKind.TIMETABLE, DailyNotificationSettings(true, minutes))
        repository.notification(NotificationKind.MEALS, DailyNotificationSettings(true, minutes))
        eventually { runBlocking { repository.preferences.first().mealNotification.minutes == minutes } }
        compose.waitForIdle()
        val scheduler = DailyNotificationScheduler(context)
        assertTrue("Grant SCHEDULE_EXACT_ALARM to the isolated test package", scheduler.canScheduleExactly())
        scheduler.synchronize(repository.preferences.first())
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(500)
        val alarmIntent = Intent(context, DailyAlarmReceiver::class.java).setAction("${context.packageName}.daily.timetable")
            .putExtra("kind", NotificationKind.TIMETABLE.name).putExtra("date", today.toString()).putExtra("minutes", minutes)
        val alarm = PendingIntent.getBroadcast(context, NotificationKind.TIMETABLE.ordinal, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_000, alarm)
        eventually { manager.activeNotifications.size == 2 }
        val posted = manager.activeNotifications.associateBy { it.id }
        val scheduleNotification = posted.getValue(DailyNotificationPublisher.id(NotificationKind.TIMETABLE))
        val mealNotification = posted.getValue(DailyNotificationPublisher.id(NotificationKind.MEALS))
        assertEquals("오늘의 시간표에요", scheduleNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("1교시 08:10 알림수학 이선생\n2교시 09:10 알림영어 김선생", scheduleNotification.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals("오늘의 급식이에요", mealNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("중식\n검증점심밥\n검증점심국\n\n석식\n검증저녁밥\n검증저녁국", mealNotification.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertNotEquals(scheduleNotification.notification.channelId, mealNotification.notification.channelId)
        eventually { runBlocking { repository.notificationDelivered(NotificationKind.TIMETABLE, today) && repository.notificationDelivered(NotificationKind.MEALS, today) } }
        alarm.send()
        SystemClock.sleep(2_000)
        assertEquals(scheduleNotification.postTime, manager.activeNotifications.first { it.id == scheduleNotification.id }.postTime)

        scheduleNotification.notification.contentIntent.send()
        waitFor(hasTestTag("today-viewport"))
        mealNotification.notification.contentIntent.send()
        waitFor(hasText("오늘은 무슨 메뉴?"))
        compose.onNodeWithText("급식 학교 변경").assertDoesNotExist()
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithContentDescription("시간표 알림").performScrollTo().performClick()
        eventually { manager.activeNotifications.none { it.id == scheduleNotification.id } }
        assertTrue(manager.activeNotifications.any { it.id == mealNotification.id })
        assertFalse(repository.preferences.first().timetableNotification.enabled)
        assertTrue(repository.preferences.first().mealNotification.enabled)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("설정"))
        compose.onNodeWithText("설정", useUnmergedTree = false).performClick()
        compose.onNodeWithContentDescription("시간표 알림").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("급식 알림").performScrollTo().assertIsOn()
        repository.notification(NotificationKind.MEALS, DailyNotificationSettings(false, minutes))
        manager.cancelAll()
    }
}

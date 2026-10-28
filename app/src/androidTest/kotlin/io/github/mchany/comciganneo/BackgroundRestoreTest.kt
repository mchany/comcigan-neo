package io.github.mchany.comciganneo

import android.os.Bundle
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import android.app.NotificationManager
import io.github.mchany.comciganneo.data.AppRepository
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.delay
import java.time.LocalDate
import io.github.mchany.comciganneo.model.NotificationKind
import io.github.mchany.comciganneo.model.SchoolZone
import io.github.mchany.comciganneo.model.schoolToday
import io.github.mchany.comciganneo.widgets.MealsWidgetProvider
import io.github.mchany.comciganneo.widgets.TimetableWidgetProvider
import io.github.mchany.comciganneo.notifications.DailyNotificationScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Prepare the unoptimized .smoke APK; the host script upgrades it to the real R8 release APK. */
@RunWith(AndroidJUnit4::class)
class BackgroundRestoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf(
        "io.github.mchany.comciganneo.notifications.DailyNotificationWorker",
        "io.github.mchany.comciganneo.widgets.WidgetRefreshWorker",
    )

    @Test fun prepareUpgrade() = runBlocking {
        check(context.packageName.endsWith(".smoke"))
        seedWidgetData(context)
        val repository = (context.applicationContext as NeoApplication).repository
        val due = ZonedDateTime.now(SchoolZone).plusMinutes(2).withSecond(0).withNano(0)
        val minutes = due.hour * 60 + due.minute
        val legacyReceipt = InstrumentationRegistry.getArguments().getString("legacyReceipt") == "true"
        if (legacyReceipt) {
            for (kind in NotificationKind.entries) {
                repository.updateNotification(kind, enabled = true, minutes = (minutes + 1439) % 1440)
                writeLegacyReceipt(repository, kind, schoolToday())
            }
        }
        NotificationKind.entries.forEach { repository.updateNotification(it, enabled = true, minutes = minutes) }
        DailyNotificationScheduler(context).synchronize(repository.preferences.first())
        val host = AppWidgetHost(context, 7171)
        val manager = AppWidgetManager.getInstance(context)
        listOf(TimetableWidgetProvider::class.java, MealsWidgetProvider::class.java).forEach { provider ->
            host.allocateAppWidgetId().also { id ->
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
            }
        }
        val work = WorkManager.getInstance(context)
        if (legacyReceipt) {
            val input = Data.Builder().putString("kind", NotificationKind.MEALS.name)
                .putString("date", schoolToday().toString()).putInt("minutes", minutes).build()
            val worker = Class.forName(names[0]).asSubclass(ListenableWorker::class.java)
            val probe = OneTimeWorkRequest.Builder(worker).setInputData(input).build()
            work.enqueue(probe).result.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (checkNotNull(work.getWorkInfoById(probe.id).get()).state.isFinished.not() && System.nanoTime() < deadline) delay(50)
            assertTrue("Old version must reproduce the same-day time-change suppression",
                checkNotNull(work.getWorkInfoById(probe.id).get()).state == androidx.work.WorkInfo.State.SUCCEEDED &&
                    context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        }
        val ids = names.map { name ->
            // Recreate using exactly the name stored by the previous unoptimized app.
            val worker = Class.forName(name).asSubclass(ListenableWorker::class.java)
            worker.getConstructor(android.content.Context::class.java, androidx.work.WorkerParameters::class.java)
            val input = Data.Builder().putString("kind", NotificationKind.MEALS.name)
                .putString("date", schoolToday().toString()).putInt("minutes", minutes).build()
            val request = OneTimeWorkRequest.Builder(worker).setInputData(input)
                .setInitialDelay(60, TimeUnit.SECONDS).build()
            work.enqueue(request).result.get(5, TimeUnit.SECONDS)
            request.id.toString()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("restoreWorkIds", ids.joinToString(","))
            putString("originalPid", Process.myPid().toString())
            putString("alarmDue", due.toInstant().toEpochMilli().toString())
            putString("legacyDateReceiptVerified", legacyReceipt.toString())
        })
    }

    private suspend fun writeLegacyReceipt(repository: AppRepository, kind: NotificationKind, date: LocalDate) =
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            // This path deliberately runs in the saved version-6 baseline APK, not the fixed app.
            repository.javaClass.getMethod("markNotificationDelivered", NotificationKind::class.java,
                LocalDate::class.java, Continuation::class.java).invoke(repository, kind, date, continuation)
        }
}

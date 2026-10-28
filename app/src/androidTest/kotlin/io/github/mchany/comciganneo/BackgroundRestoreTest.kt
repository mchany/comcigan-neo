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
        })
    }
}

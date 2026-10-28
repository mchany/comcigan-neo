package io.github.mchany.comciganneo.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.*

abstract class NeoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onDeleted(context: Context, ids: IntArray) { NeoWidgets.synchronize(context) }
    override fun onDisabled(context: Context) { NeoWidgets.synchronize(context) }
    private fun update(context: Context) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { NeoWidgets.synchronize(context); WidgetRenderer.updateAll(context); NeoWidgets.requestRefresh(context) } }
            catch (_: Exception) { /* A resize/removal may invalidate a host during delivery. */ }
            finally { result.finish() }
        }
    }
}
class TimetableWidgetProvider : NeoWidgetProvider()
class WideTimetableWidgetProvider : NeoWidgetProvider()
class MealsWidgetProvider : NeoWidgetProvider()
class WideMealsWidgetProvider : NeoWidgetProvider()

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NeoWidgets.isUpdateAction(intent.action)) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { NeoWidgets.synchronize(context); WidgetRenderer.updateAll(context); NeoWidgets.requestRefresh(context, intent.action == NeoWidgets.ACTION_REFRESH) } }
            catch (_: Exception) { /* Retry via the next host or periodic update. */ }
            finally { result.finish() }
        }
    }
}

package io.github.mchany.comciganneo.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.*

abstract class NeoWidgetProvider : AppWidgetProvider() {
    /**
     * 역할: 앱 위젯 화면을 갱신한다.
     * 입력: context: Context, manager: AppWidgetManager, ids: IntArray.
     * 출력: 없음(Unit).
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    /**
     * 역할: 위젯 크기나 옵션이 바뀐 뒤 화면을 갱신한다.
     * 입력: context: Context, manager: AppWidgetManager, id: Int, options: Bundle.
     * 출력: 없음(Unit).
     */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    /**
     * 역할: 삭제된 위젯을 반영해 등록 상태를 동기화한다.
     * 입력: context: Context, ids: IntArray.
     * 출력: 없음(Unit).
     */
    override fun onDeleted(context: Context, ids: IntArray) { NeoWidgets.synchronize(context) }
    /**
     * 역할: 모든 위젯이 제거된 상태를 시스템에 반영한다.
     * 입력: context: Context.
     * 출력: 없음(Unit).
     */
    override fun onDisabled(context: Context) { NeoWidgets.synchronize(context) }
    /**
     * 역할: 저장된 위젯 자료를 읽고 등록된 위젯 화면을 갱신한다.
     * 입력: context: Context.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 위젯 갱신과 시스템 부팅 관련 브로드캐스트를 처리한다.
     * 입력: context: Context, intent: Intent.
     * 출력: 없음(Unit).
     */
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

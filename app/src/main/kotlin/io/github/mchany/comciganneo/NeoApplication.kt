package io.github.mchany.comciganneo

import android.app.Application
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import io.github.mchany.comciganneo.cache.CacheCleanupWorker
import io.github.mchany.comciganneo.data.AppRepository
import io.github.mchany.comciganneo.notifications.DailyNotificationScheduler
import io.github.mchany.comciganneo.notifications.DailyNotificationPublisher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.mchany.comciganneo.widgets.NeoWidgets
import io.github.mchany.comciganneo.widgets.WidgetRenderer

class NeoApplication : Application() {
    val repository by lazy { AppRepository(this, BuildConfig.NEIS_API_KEY) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 역할: 위젯 목록 팩토리 초기화 시 별도 작업을 하지 않는다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    override fun onCreate() {
        super.onCreate()
        CacheCleanupWorker.schedule(this)
        DailyNotificationPublisher.createChannels(this)
        scope.launch { repository.preferences.collect { DailyNotificationScheduler(this@NeoApplication).synchronize(it) } }
        scope.launch {
            repository.preferences.map { listOf(it.selection, it.mealSchools, it.themeMode, it.dynamicColor, it.showTeachers, it.widgetPalette) }
                .distinctUntilChanged().collect {
                    NeoWidgets.synchronize(this@NeoApplication)
                    WidgetRenderer.updateAll(this@NeoApplication)
                    NeoWidgets.requestRefresh(this@NeoApplication)
                }
        }
        scope.launch { repository.cacheChanges.collect { WidgetRenderer.updateAll(this@NeoApplication) } }
        if (Build.VERSION.SDK_INT >= 31) {
            WallpaperManager.getInstance(this).addOnColorsChangedListener(WallpaperManager.OnColorsChangedListener { _, _ ->
                scope.launch { WidgetRenderer.updateAll(this@NeoApplication) }
            }, Handler(mainLooper))
        }
    }

    /**
     * 역할: 저장된 알림 설정으로 시스템 예약을 다시 동기화한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    fun refreshNotificationSchedules() {
        scope.launch { DailyNotificationScheduler(this@NeoApplication).synchronize(repository.preferences.first()) }
    }
}

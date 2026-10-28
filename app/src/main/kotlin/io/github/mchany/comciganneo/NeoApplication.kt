package io.github.mchany.comciganneo

import android.app.Application
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
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

    override fun onCreate() {
        super.onCreate()
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

    fun refreshNotificationSchedules() {
        scope.launch { DailyNotificationScheduler(this@NeoApplication).synchronize(repository.preferences.first()) }
    }
}

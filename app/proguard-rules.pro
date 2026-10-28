# WorkManager persists these names in its database and recreates workers by reflection.
# Retain their names across app upgrades, even if no current UI path enqueues them.
# Other methods remain eligible for optimization; library consumer rules cover Room,
# Kotlin serialization, AndroidX Startup and framework entry points in the manifest.
-keep,allowoptimization class io.github.mchany.comciganneo.notifications.DailyNotificationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep,allowoptimization class io.github.mchany.comciganneo.widgets.WidgetRefreshWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

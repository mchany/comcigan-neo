package io.github.mchany.comciganneo.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import io.github.mchany.comciganneo.comcigan.*
import io.github.mchany.comciganneo.model.*
import io.github.mchany.comciganneo.neis.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.settings by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class AppRepository(context: Context, private val defaultNeisKey: String = "") {
    private val context = context.applicationContext
    private val database = Room.databaseBuilder(this.context, TimetableDatabase::class.java, "timetables.db")
        .addMigrations(Migration1To2).build()
    private val client = ComciganClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val selectionKey = stringPreferencesKey("selection")
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val teachersKey = booleanPreferencesKey("show_teachers")
    private val mealSchoolsKey = stringPreferencesKey("meal_schools")
    private val initialSetupKey = booleanPreferencesKey("initial_setup_pending")
    private val notificationSetupKey = booleanPreferencesKey("notification_setup_pending")
    private val mealLock = Mutex()
    private val neisTransport = OkHttpNeisTransport()
    private val cacheEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheChanges: SharedFlow<Unit> = cacheEvents.asSharedFlow()

    val preferences: Flow<AppPreferences> = this.context.settings.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { data ->
        AppPreferences(
            selection = data[selectionKey]?.let { runCatching { json.decodeFromString<ClassSelection>(it) }.getOrNull() },
            themeMode = runCatching { ThemeMode.valueOf(data[themeKey].orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = data[dynamicKey] ?: true,
            showTeachers = data[teachersKey] ?: true,
            mealSchools = data[mealSchoolsKey]?.let {
                runCatching { json.decodeFromString<Map<String, NeisSchoolRef>>(it) }.getOrNull()
            } ?: emptyMap(),
            mealKeyConfigured = defaultNeisKey.isNotBlank(),
            initialSetupPending = data[initialSetupKey] ?: false,
            notificationSetupPending = data[notificationSetupKey] ?: (data[initialSetupKey] ?: false),
            timetableNotification = readNotification(data, NotificationKind.TIMETABLE),
            mealNotification = readNotification(data, NotificationKind.MEALS),
            widgetPalette = runCatching { WidgetPalette.valueOf(data[stringPreferencesKey("widget_palette")].orEmpty()) }.getOrDefault(WidgetPalette.APP),
        )
    }
    suspend fun select(selection: ClassSelection) {
        context.settings.edit { data ->
            val previous = data[selectionKey]?.let { runCatching { json.decodeFromString<ClassSelection>(it) }.getOrNull() }
            if (previous == null) {
                data[initialSetupKey] = true
                data[notificationSetupKey] = true
            }
            data[selectionKey] = json.encodeToString(selection)
        }
    }
    suspend fun finishMealSetup() {
        context.settings.edit { it[initialSetupKey] = false; it[notificationSetupKey] = true }
    }
    suspend fun finishInitialSetup() {
        context.settings.edit { it[initialSetupKey] = false; it[notificationSetupKey] = false }
    }
    suspend fun returnToMealSetup() { context.settings.edit { it[initialSetupKey] = true } }
    private fun readNotification(data: Preferences, kind: NotificationKind) = DailyNotificationSettings(
        enabled = data[booleanPreferencesKey("notification_${kind.key}_enabled")] ?: false,
        minutes = (data[intPreferencesKey("notification_${kind.key}_minutes")] ?: kind.defaultMinutes).coerceIn(0, 1439),
    )
    suspend fun updateNotification(kind: NotificationKind, enabled: Boolean? = null, minutes: Int? = null) {
        require(minutes == null || minutes in 0..1439)
        context.settings.edit {
            enabled?.let { value -> it[booleanPreferencesKey("notification_${kind.key}_enabled")] = value }
            minutes?.let { value -> it[intPreferencesKey("notification_${kind.key}_minutes")] = value }
        }
    }
    suspend fun notificationDelivered(kind: NotificationKind, date: LocalDate): Boolean =
        context.settings.data.first()[stringPreferencesKey("notification_${kind.key}_delivered")] == date.toString()
    suspend fun markNotificationDelivered(kind: NotificationKind, date: LocalDate) {
        context.settings.edit { it[stringPreferencesKey("notification_${kind.key}_delivered")] = date.toString() }
    }
    suspend fun theme(mode: ThemeMode) { context.settings.edit { it[themeKey] = mode.name } }
    suspend fun dynamic(enabled: Boolean) { context.settings.edit { it[dynamicKey] = enabled } }
    suspend fun teachers(enabled: Boolean) { context.settings.edit { it[teachersKey] = enabled } }
    suspend fun widgetPalette(palette: WidgetPalette) { context.settings.edit { it[stringPreferencesKey("widget_palette")] = palette.name } }
    suspend fun search(query: String): List<School> = client.searchSchools(query)
    suspend fun preview(school: School): TimetableSnapshot = client.fetch(ClassSelection(school, 1, 1), 0)
    suspend fun cached(selection: ClassSelection, week: Int): TimetableSnapshot? =
        database.snapshots().find(selection.school.code, selection.grade, selection.classNumber, weekStart(offset = week).toString())
            ?.let { runCatching { json.decodeFromString<TimetableSnapshot>(it.json) }.getOrNull() }
    suspend fun refresh(selection: ClassSelection, week: Int): TimetableSnapshot {
        val snapshot = client.fetch(selection, week)
        database.snapshots().save(SnapshotEntity(selection.school.code, selection.grade, selection.classNumber,
            snapshot.weekStart, json.encodeToString(snapshot)))
        database.snapshots().prune(weekStart(offset = -4).toString())
        cacheEvents.tryEmit(Unit)
        return snapshot
    }
    private fun mealClient(): NeisClient = NeisClient(defaultNeisKey, neisTransport)
    suspend fun searchMealSchools(query: String) = mealClient().searchSchools(query)
    suspend fun setMealSchool(comciganCode: Int, school: NeisSchoolRef?) {
        context.settings.edit { data ->
            val schools = data[mealSchoolsKey]?.let {
                runCatching { json.decodeFromString<Map<String, NeisSchoolRef>>(it) }.getOrNull()
            }.orEmpty().toMutableMap()
            if (school == null) schools.remove(comciganCode.toString()) else schools[comciganCode.toString()] = school
            data[mealSchoolsKey] = json.encodeToString(schools)
            if (school != null) {
                if (data[initialSetupKey] == true) data[notificationSetupKey] = true
                data[initialSetupKey] = false
            }
        }
    }
    suspend fun cachedMeals(school: NeisSchoolRef, date: LocalDate): MealSnapshot? =
        database.meals().find(school.officeCode, school.schoolCode, weekStart(date).toString())?.let {
            runCatching { json.decodeFromString<MealSnapshot>(it.json) }.getOrNull()
        }?.takeIf { it.school == school && it.weekStart == weekStart(date).toString() }
    suspend fun refreshMeals(school: NeisSchoolRef, date: LocalDate, force: Boolean = false): MealSnapshot = mealLock.withLock {
        val cached = cachedMeals(school, date)
        if (!force && cached?.isFresh(date) == true) return@withLock cached
        val week = weekStart(date)
        val meals = mealClient().meals(school, week, week.plusDays(6))
        currentCoroutineContext().ensureActive()
        val snapshot = MealSnapshot(school, week.toString(), System.currentTimeMillis(), meals)
        database.meals().save(MealSnapshotEntity(school.officeCode, school.schoolCode, week.toString(), json.encodeToString(snapshot)))
        database.meals().prune(weekStart(offset = -4).toString())
        cacheEvents.tryEmit(Unit)
        snapshot
    }
    suspend fun clearMealCache() = mealLock.withLock { database.meals().clear(); cacheEvents.tryEmit(Unit); Unit }
    suspend fun clearCache() {
        mealLock.withLock { database.withTransaction { database.snapshots().clear(); database.meals().clear() } }
        cacheEvents.tryEmit(Unit)
    }
}

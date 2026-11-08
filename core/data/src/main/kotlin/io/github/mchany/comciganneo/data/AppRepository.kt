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
    /**
     * 역할: 선택한 학급을 저장하고 해당 주 시간표를 불러온다.
     * 입력: selection: ClassSelection.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 초기 급식 설정을 완료한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    suspend fun finishMealSetup() {
        context.settings.edit { it[initialSetupKey] = false; it[notificationSetupKey] = true }
    }
    /**
     * 역할: 앱 초기 설정을 완료한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    suspend fun finishInitialSetup() {
        context.settings.edit { it[initialSetupKey] = false; it[notificationSetupKey] = false }
    }
    /**
     * 역할: 급식 학교 초기 설정 상태를 저장한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    suspend fun returnToMealSetup() { context.settings.edit { it[initialSetupKey] = true } }
    /**
     * 역할: 저장소에서 알림 설정을 읽는다.
     * 입력: data: Preferences, kind: NotificationKind.
     * 출력: DailyNotificationSettings 값.
     */
    private fun readNotification(data: Preferences, kind: NotificationKind) = DailyNotificationSettings(
        enabled = data[booleanPreferencesKey("notification_${kind.key}_enabled")] ?: false,
        minutes = (data[intPreferencesKey("notification_${kind.key}_minutes")] ?: kind.defaultMinutes).coerceIn(0, 1439),
    )
    /**
     * 역할: 알림 사용 여부와 시각을 저장한다.
     * 입력: kind: NotificationKind, enabled: Boolean?, minutes: Int?.
     * 출력: 없음(Unit).
     */
    suspend fun updateNotification(kind: NotificationKind, enabled: Boolean? = null, minutes: Int? = null) {
        require(minutes == null || minutes in 0..1439)
        context.settings.edit {
            enabled?.let { value -> it[booleanPreferencesKey("notification_${kind.key}_enabled")] = value }
            minutes?.let { value -> it[intPreferencesKey("notification_${kind.key}_minutes")] = value }
        }
    }
    /**
     * 역할: 해당 알림 시각의 발송 여부를 확인한다.
     * 입력: kind: NotificationKind, date: LocalDate, minutes: Int.
     * 출력: Boolean 값.
     */
    suspend fun notificationDelivered(kind: NotificationKind, date: LocalDate, minutes: Int): Boolean =
        notificationDeliverySlot(date, minutes) in context.settings.data.first()[stringSetPreferencesKey("notification_${kind.key}_delivered_slots")].orEmpty()
    /**
     * 역할: 해당 알림 시각을 발송 완료로 기록한다.
     * 입력: kind: NotificationKind, date: LocalDate, minutes: Int.
     * 출력: 없음(Unit).
     */
    suspend fun markNotificationDelivered(kind: NotificationKind, date: LocalDate, minutes: Int) {
        context.settings.edit { data ->
            val key = stringSetPreferencesKey("notification_${kind.key}_delivered_slots")
            data[key] = recordNotificationDelivery(data[key].orEmpty(), date, minutes)
            // The old date-only record cannot identify which configured time was delivered.
            data.remove(stringPreferencesKey("notification_${kind.key}_delivered"))
        }
    }
    /**
     * 역할: 앱 테마 설정을 저장한다.
     * 입력: mode: ThemeMode.
     * 출력: 없음(Unit).
     */
    suspend fun theme(mode: ThemeMode) { context.settings.edit { it[themeKey] = mode.name } }
    /**
     * 역할: 동적 색상 사용 설정을 저장한다.
     * 입력: enabled: Boolean.
     * 출력: 없음(Unit).
     */
    suspend fun dynamic(enabled: Boolean) { context.settings.edit { it[dynamicKey] = enabled } }
    /**
     * 역할: 교사 표시 설정을 저장한다.
     * 입력: enabled: Boolean.
     * 출력: 없음(Unit).
     */
    suspend fun teachers(enabled: Boolean) { context.settings.edit { it[teachersKey] = enabled } }
    /**
     * 역할: 위젯 색상 팔레트 설정을 저장한다.
     * 입력: palette: WidgetPalette.
     * 출력: 없음(Unit).
     */
    suspend fun widgetPalette(palette: WidgetPalette) { context.settings.edit { it[stringPreferencesKey("widget_palette")] = palette.name } }
    /**
     * 역할: 학교 검색어로 검색 결과를 조회한다.
     * 입력: query: String.
     * 출력: List<School> 값.
     */
    suspend fun search(query: String): List<School> = client.searchSchools(query)
    /**
     * 역할: 학교의 기본 학년과 반으로 시간표 미리보기 자료를 조회한다.
     * 입력: school: School.
     * 출력: TimetableSnapshot 값.
     */
    suspend fun preview(school: School): TimetableSnapshot = client.fetch(ClassSelection(school, 1, 1), 0)
    /**
     * 역할: 저장된 시간표 스냅샷을 조회한다.
     * 입력: selection: ClassSelection, week: Int.
     * 출력: TimetableSnapshot? 값.
     */
    suspend fun cached(selection: ClassSelection, week: Int): TimetableSnapshot? =
        cachedTimetableSnapshot(database.snapshots().forClass(selection.school.code, selection.grade, selection.classNumber)
            .mapNotNull { runCatching { json.decodeFromString<TimetableSnapshot>(it.json) }.getOrNull() }, selection, week)
    /**
     * 역할: 시간표 자료를 새로 조회해 저장한다.
     * 입력: selection: ClassSelection, week: Int.
     * 출력: TimetableSnapshot 값.
     */
    suspend fun refresh(selection: ClassSelection, week: Int): TimetableSnapshot {
        val snapshot = client.fetch(selection, week)
        database.snapshots().save(SnapshotEntity(selection.school.code, selection.grade, selection.classNumber,
            snapshot.weekStart, json.encodeToString(snapshot)))
        database.snapshots().prune(weekStart(offset = -4).toString())
        cacheEvents.tryEmit(Unit)
        return snapshot
    }
    /**
     * 역할: 급식 조회용 NEIS 클라이언트를 생성한다.
     * 입력: 없음.
     * 출력: NeisClient 값.
     */
    private fun mealClient(): NeisClient = NeisClient(defaultNeisKey, neisTransport)
    /**
     * 역할: 급식 학교 검색 결과를 불러온다.
     * 입력: query: String.
     * 출력: List<NeisSchoolRef> 값.
     */
    suspend fun searchMealSchools(query: String) = mealClient().searchSchools(query)
    /**
     * 역할: 시간표 학교에 연결할 급식 학교를 저장하거나 연결을 해제한다.
     * 입력: comciganCode: Int, school: NeisSchoolRef?.
     * 출력: 없음(Unit).
     */
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
    /**
     * 역할: 저장된 급식 스냅샷을 조회한다.
     * 입력: school: NeisSchoolRef, date: LocalDate.
     * 출력: MealSnapshot? 값.
     */
    suspend fun cachedMeals(school: NeisSchoolRef, date: LocalDate): MealSnapshot? =
        database.meals().find(school.officeCode, school.schoolCode, weekStart(date).toString())?.let {
            runCatching { json.decodeFromString<MealSnapshot>(it.json) }.getOrNull()
        }?.takeIf { it.school == school && it.weekStart == weekStart(date).toString() }
    /**
     * 역할: 급식 자료를 강제로 새로 조회한다.
     * 입력: school: NeisSchoolRef, date: LocalDate, force: Boolean.
     * 출력: MealSnapshot 값.
     */
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
    /**
     * 역할: 저장된 급식 자료를 비운다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    suspend fun clearMealCache() = mealLock.withLock { database.meals().clear(); cacheEvents.tryEmit(Unit); Unit }
    /**
     * 역할: 저장된 시간표 자료를 비운다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    suspend fun clearCache() {
        mealLock.withLock { database.withTransaction { database.snapshots().clear(); database.meals().clear() } }
        cacheEvents.tryEmit(Unit)
    }
}

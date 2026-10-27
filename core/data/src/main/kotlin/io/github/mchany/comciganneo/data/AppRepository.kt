package io.github.mchany.comciganneo.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import io.github.mchany.comciganneo.comcigan.*
import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.settings by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Entity(tableName = "snapshots", primaryKeys = ["schoolCode", "grade", "classNumber", "weekStart"])
data class SnapshotEntity(
    val schoolCode: Int,
    val grade: Int,
    val classNumber: Int,
    val weekStart: String,
    val json: String,
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots WHERE schoolCode = :school AND grade = :grade AND classNumber = :classNumber AND weekStart = :week LIMIT 1")
    suspend fun find(school: Int, grade: Int, classNumber: Int, week: String): SnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(snapshot: SnapshotEntity)
    @Query("DELETE FROM snapshots") suspend fun clear()
    @Query("DELETE FROM snapshots WHERE weekStart < :oldestWeek") suspend fun prune(oldestWeek: String)
}

@Database(entities = [SnapshotEntity::class], version = 1, exportSchema = true)
abstract class TimetableDatabase : RoomDatabase() { abstract fun snapshots(): SnapshotDao }

class AppRepository(context: Context) {
    private val context = context.applicationContext
    private val database = Room.databaseBuilder(this.context, TimetableDatabase::class.java, "timetables.db").build()
    private val client = ComciganClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val selectionKey = stringPreferencesKey("selection")
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val teachersKey = booleanPreferencesKey("show_teachers")

    val preferences: Flow<AppPreferences> = this.context.settings.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { data ->
        AppPreferences(
            selection = data[selectionKey]?.let { runCatching { json.decodeFromString<ClassSelection>(it) }.getOrNull() },
            themeMode = runCatching { ThemeMode.valueOf(data[themeKey].orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = data[dynamicKey] ?: true,
            showTeachers = data[teachersKey] ?: true,
        )
    }
    suspend fun select(selection: ClassSelection) { context.settings.edit { it[selectionKey] = json.encodeToString(selection) } }
    suspend fun theme(mode: ThemeMode) { context.settings.edit { it[themeKey] = mode.name } }
    suspend fun dynamic(enabled: Boolean) { context.settings.edit { it[dynamicKey] = enabled } }
    suspend fun teachers(enabled: Boolean) { context.settings.edit { it[teachersKey] = enabled } }
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
        return snapshot
    }
    suspend fun clearCache() { database.snapshots().clear() }
}

fun userMessage(error: Throwable): String = when (error) {
    is TimetableUnavailable -> "아직 게시된 시간표가 없어요. 나중에 다시 확인해 주세요."
    is StructureChanged -> "시간표 형식이 바뀌었거나 자료를 읽을 수 없어요. 앱 업데이트를 확인해 주세요."
    is IOException -> "연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요."
    else -> "작업을 완료하지 못했어요. 잠시 후 다시 시도해 주세요."
}

package io.github.mchany.comciganneo.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(tableName = "meal_snapshots", primaryKeys = ["officeCode", "schoolCode", "weekStart"])
data class MealSnapshotEntity(val officeCode: String, val schoolCode: String, val weekStart: String, val json: String)

@Dao
interface MealSnapshotDao {
    @Query("SELECT * FROM meal_snapshots WHERE officeCode = :office AND schoolCode = :school AND weekStart = :week LIMIT 1")
    suspend fun find(office: String, school: String, week: String): MealSnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(snapshot: MealSnapshotEntity)
    @Query("DELETE FROM meal_snapshots") suspend fun clear()
    @Query("DELETE FROM meal_snapshots WHERE weekStart < :oldestWeek") suspend fun prune(oldestWeek: String)
}

val Migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `meal_snapshots` (`officeCode` TEXT NOT NULL, `schoolCode` TEXT NOT NULL, `weekStart` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`officeCode`, `schoolCode`, `weekStart`))")
    }
}

@Database(entities = [SnapshotEntity::class, MealSnapshotEntity::class], version = 2, exportSchema = true)
abstract class TimetableDatabase : RoomDatabase() {
    abstract fun snapshots(): SnapshotDao
    abstract fun meals(): MealSnapshotDao
}

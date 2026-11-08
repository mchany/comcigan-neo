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
    @Query("SELECT * FROM snapshots WHERE schoolCode = :school AND grade = :grade AND classNumber = :classNumber ORDER BY weekStart DESC LIMIT 16")
    suspend fun forClass(school: Int, grade: Int, classNumber: Int): List<SnapshotEntity>
    @Query("SELECT * FROM snapshots WHERE schoolCode = :school AND grade = :grade AND classNumber = :classNumber AND weekStart = :week LIMIT 1")
    /**
     * 역할: 학교, 학년, 반, 주차가 일치하는 시간표 스냅샷을 조회한다.
     * 입력: school: Int, grade: Int, classNumber: Int, week: String.
     * 출력: SnapshotEntity? 값.
     */
    suspend fun find(school: Int, grade: Int, classNumber: Int, week: String): SnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * 역할: 시간표 스냅샷을 저장하거나 같은 키의 기존 자료를 교체한다.
     * 입력: snapshot: SnapshotEntity.
     * 출력: 없음(Unit).
     */
    suspend fun save(snapshot: SnapshotEntity)
    /**
     * 역할: 시간표 스냅샷 테이블의 모든 항목을 삭제한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Query("DELETE FROM snapshots") suspend fun clear()
    /**
     * 역할: 지정된 주차보다 오래된 시간표 스냅샷을 삭제한다.
     * 입력: oldestWeek: String.
     * 출력: 없음(Unit).
     */
    @Query("DELETE FROM snapshots WHERE weekStart < :oldestWeek") suspend fun prune(oldestWeek: String)
}

@Entity(tableName = "meal_snapshots", primaryKeys = ["officeCode", "schoolCode", "weekStart"])
data class MealSnapshotEntity(val officeCode: String, val schoolCode: String, val weekStart: String, val json: String)

@Dao
interface MealSnapshotDao {
    @Query("SELECT * FROM meal_snapshots WHERE officeCode = :office AND schoolCode = :school AND weekStart = :week LIMIT 1")
    /**
     * 역할: 교육청, 학교, 주차가 일치하는 급식 스냅샷을 조회한다.
     * 입력: office: String, school: String, week: String.
     * 출력: MealSnapshotEntity? 값.
     */
    suspend fun find(office: String, school: String, week: String): MealSnapshotEntity?
    /**
     * 역할: 급식 스냅샷을 저장하거나 같은 키의 기존 자료를 교체한다.
     * 입력: snapshot: MealSnapshotEntity.
     * 출력: 없음(Unit).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(snapshot: MealSnapshotEntity)
    /**
     * 역할: 급식 스냅샷 테이블의 모든 항목을 삭제한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Query("DELETE FROM meal_snapshots") suspend fun clear()
    /**
     * 역할: 지정된 주차보다 오래된 급식 스냅샷을 삭제한다.
     * 입력: oldestWeek: String.
     * 출력: 없음(Unit).
     */
    @Query("DELETE FROM meal_snapshots WHERE weekStart < :oldestWeek") suspend fun prune(oldestWeek: String)
}

val Migration1To2 = object : Migration(1, 2) {
    /**
     * 역할: 데이터베이스 구조를 새 버전으로 이전한다.
     * 입력: db: SupportSQLiteDatabase.
     * 출력: 없음(Unit).
     */
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `meal_snapshots` (`officeCode` TEXT NOT NULL, `schoolCode` TEXT NOT NULL, `weekStart` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`officeCode`, `schoolCode`, `weekStart`))")
    }
}

@Database(entities = [SnapshotEntity::class, MealSnapshotEntity::class], version = 2, exportSchema = true)
abstract class TimetableDatabase : RoomDatabase() {
    /**
     * 역할: Room에서 시간표 스냅샷 DAO를 제공한다.
     * 입력: 없음.
     * 출력: SnapshotDao 값.
     */
    abstract fun snapshots(): SnapshotDao
    /**
     * 역할: 학교와 날짜 범위에 해당하는 급식 목록을 조회한다.
     * 입력: 없음.
     * 출력: MealSnapshotDao 값.
     */
    abstract fun meals(): MealSnapshotDao
}

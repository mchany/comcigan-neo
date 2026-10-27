package io.github.mchany.comciganneo

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mchany.comciganneo.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealMigrationTest {
    @Test fun upgradeFromBetaPreservesTimetablesAndCreatesMealCache() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".smoke"))
        val name = "meal-migration-test.db"
        context.deleteDatabase(name)
        val oldFile = context.getDatabasePath(name)
        oldFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(oldFile, null).use { db ->
            // Exact table shape exported by Room in the 0.1.0-beta v1 schema.
            db.execSQL("CREATE TABLE IF NOT EXISTS `snapshots` (`schoolCode` INTEGER NOT NULL, `grade` INTEGER NOT NULL, `classNumber` INTEGER NOT NULL, `weekStart` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`schoolCode`, `grade`, `classNumber`, `weekStart`))")
            db.execSQL("INSERT INTO snapshots VALUES (42, 1, 2, '2026-10-26', 'preserved')")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, name).addMigrations(Migration1To2).build()
        try {
            assertEquals("preserved", database.snapshots().find(42, 1, 2, "2026-10-26")?.json)
            database.meals().save(MealSnapshotEntity("G10", "001", "2026-10-26", "meals"))
            assertEquals("meals", database.meals().find("G10", "001", "2026-10-26")?.json)
            assertNull(database.meals().find("B10", "001", "2026-10-26"))
            database.meals().clear()
            assertEquals("preserved", database.snapshots().find(42, 1, 2, "2026-10-26")?.json)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}

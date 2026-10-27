package io.github.mchany.comciganneo.model

import org.junit.Test
import java.time.LocalDate
import kotlin.test.*

class MealSnapshotTest {
    private val date = LocalDate.of(2026, 10, 27)
    private val entry = MealEntry(date.toString(), "2", "중식", "밥", listOf("밥"))
    private val snapshot = MealSnapshot(NeisSchoolRef("G10", "001", "학교", "교육청", "주소"), "2026-10-26", 1_000, listOf(entry))
    @Test fun emptyDatesExpireSoonerAndClockRollbackIsStale() {
        assertTrue(snapshot.isFresh(date, 1_001))
        assertFalse(snapshot.isFresh(date, 999))
        assertTrue(snapshot.isFresh(date, 3_601_000))
        assertFalse(snapshot.isFresh(date.plusDays(1), 3_601_000))
        assertFalse(snapshot.isFresh(date, 21_601_000))
    }
    @Test fun datesDoNotBorrowPreviousMealsAcrossYearBoundary() {
        assertEquals(emptyList(), snapshot.forDate(LocalDate.of(2027, 1, 1)))
        assertEquals(LocalDate.of(2026, 12, 28), weekStart(LocalDate.of(2027, 1, 1)))
        assertEquals(listOf(entry), snapshot.forDate(date))
    }
}

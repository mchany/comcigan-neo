package io.github.mchany.comciganneo.model

import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class TimetableWeeksTest {
    private val selection = ClassSelection(School(29318,"테스트고","대전"),1,1)
    private val sunday = LocalDate.parse("2026-11-08")
    private val periods = listOf(PublishedTimetableWeek(1,"2026-11-09"),PublishedTimetableWeek(7,"2026-11-16"))
    private fun snapshot(start: String, fetched: Long = 1, weeks: List<PublishedTimetableWeek> = periods) =
        TimetableSnapshot(selection,start,fetchedAt=fetched,classCounts=listOf(1),slots=emptyList(),publishedWeeks=weeks)

    @Test fun `published periods can advance on Sunday without changing calendar and meal weeks`() {
        val first = snapshot("2026-11-09")
        assertEquals("2026-11-02",weekStart(sunday).toString())
        assertEquals(listOf("2026-11-09","2026-11-16"),first.publishedWeekStarts(sunday))
        assertTrue(first.matchesPublishedWeek(0,sunday))
        assertFalse(first.matchesPublishedWeek(1,sunday))
        assertEquals(listOf("2026-11-16"),first.publishedWeekStarts(LocalDate.parse("2026-11-16")))
    }

    @Test fun `cache uses newest catalog for both periods and ignores another class`() {
        val old = snapshot("2026-11-02",1,emptyList())
        val next = snapshot("2026-11-09",2,listOf(PublishedTimetableWeek(1,"2026-11-02"),PublishedTimetableWeek(2,"2026-11-09")))
        val refreshed = snapshot("2026-11-16",3)
        val wrongClass = refreshed.copy(selection=selection.copy(classNumber=2),fetchedAt=4)
        val rows = listOf(old,next,refreshed,wrongClass)
        val current = cachedTimetableSnapshot(rows,selection,0,sunday)!!
        assertEquals("2026-11-09",current.weekStart)
        assertEquals(periods,current.publishedWeeks)
        assertEquals(refreshed,cachedTimetableSnapshot(rows,selection,1,sunday))
        assertNull(cachedTimetableSnapshot(listOf(old,refreshed),selection,0,sunday))
    }

    @Test fun `freshness expires at Seoul midnight even within five minutes`() {
        val now = sunday.atStartOfDay(SchoolZone).toInstant().toEpochMilli()
        assertFalse(snapshot("2026-11-09",now-1).isFreshTimetable(0,now))
        assertTrue(snapshot("2026-11-09",now).isFreshTimetable(0,now+1))
        assertFalse(snapshot("2026-11-09",now).isFreshTimetable(0,now+300_000))
        assertFalse(snapshot("2026-11-09",now+1).isFreshTimetable(0,now))
    }

    @Test fun `legacy JSON remains readable and new catalogs survive serialization`() {
        val legacy = snapshot("2026-11-02",weeks=emptyList())
        val oldJson = Json.encodeToString(legacy)
        assertFalse(oldJson.contains("publishedWeeks"))
        assertEquals(legacy,Json.decodeFromString<TimetableSnapshot>(oldJson))
        assertEquals(legacy,cachedTimetableSnapshot(listOf(legacy),selection,0,sunday))
        val current = snapshot("2026-11-09")
        assertEquals(current,Json.decodeFromString<TimetableSnapshot>(Json.encodeToString(current)))
    }
}

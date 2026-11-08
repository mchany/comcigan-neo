package io.github.mchany.comciganneo.model

import java.time.Instant
import java.time.LocalDate

/** Available published periods, not an assumption that server requests 1/2 mean calendar weeks. */
fun TimetableSnapshot.publishedWeekStarts(date: LocalDate = schoolToday()): List<String> =
    if (publishedWeeks.isEmpty()) (0..1).map { weekStart(date, it).toString() }
    else publishedWeeks.map { it.start }.filter { it >= weekStart(date).toString() }.sorted().take(2)

fun TimetableSnapshot.matchesPublishedWeek(week: Int, date: LocalDate = schoolToday()): Boolean =
    weekStart == publishedWeekStarts(date).getOrNull(week)

fun TimetableSnapshot.isFreshTimetable(week: Int, now: Long = System.currentTimeMillis()): Boolean {
    val today = Instant.ofEpochMilli(now).atZone(SchoolZone).toLocalDate()
    return matchesPublishedWeek(week, today) && now - fetchedAt in 0L..299_999L &&
        Instant.ofEpochMilli(fetchedAt).atZone(SchoolZone).toLocalDate() == today
}

/** Use the newest catalog for both cache slots; never fall back to another period's snapshot. */
fun cachedTimetableSnapshot(
    snapshots: List<TimetableSnapshot>, selection: ClassSelection, week: Int, date: LocalDate = schoolToday(),
): TimetableSnapshot? {
    require(week in 0..1)
    val matching = snapshots.filter { it.selection == selection }
    val catalog = matching.maxByOrNull { it.fetchedAt } ?: return null
    val start = catalog.publishedWeekStarts(date).getOrNull(week) ?: return null
    return matching.filter { it.weekStart == start }.maxByOrNull { it.fetchedAt }
        ?.copy(publishedWeeks = catalog.publishedWeeks)
}

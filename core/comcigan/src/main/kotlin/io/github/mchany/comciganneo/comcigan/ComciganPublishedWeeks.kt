package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.PublishedTimetableWeek
import kotlinx.serialization.json.*
import java.time.DayOfWeek
import java.time.LocalDate

internal object ComciganPublishedWeeks {
    private val range = Regex("""^(\d{2}|\d{4})-(\d{1,2})-(\d{1,2})\s*~\s*(\d{2}|\d{4})-(\d{1,2})-(\d{1,2})$""")

    fun parse(data: JsonObject, responseStart: LocalDate): List<PublishedTimetableWeek> {
        val raw = data["일자자료"] ?: return emptyList() // Older response fixtures and cached payloads.
        try {
            val rows = raw.jsonArray
            require(rows.size in 1..64)
            val weeks = rows.map { item ->
                val row = item.jsonArray
                require(row.size == 2)
                val request = row[0].jsonPrimitive.int
                require(request > 0)
                val match = range.matchEntire(row[1].jsonPrimitive.content.trim()) ?: error("Unsupported date range")
                fun date(offset: Int): LocalDate {
                    val year = match.groupValues[offset]
                    // Resolve two-digit years near the response year, including century boundaries.
                    val y = if (year.length == 4) year.toInt() else {
                        val candidate = responseStart.year / 100 * 100 + year.toInt()
                        listOf(candidate - 100, candidate, candidate + 100).minBy { kotlin.math.abs(it - responseStart.year) }
                    }
                    return LocalDate.of(y, match.groupValues[offset + 1].toInt(), match.groupValues[offset + 2].toInt())
                }
                val start = date(1)
                val end = date(4)
                require(start.dayOfWeek == DayOfWeek.MONDAY && end in start..start.plusDays(6))
                PublishedTimetableWeek(request, start.toString())
            }
            require(weeks.map { it.request }.distinct().size == weeks.size)
            require(weeks.map { it.start }.distinct().size == weeks.size)
            require(weeks.any { it.start == responseStart.toString() })
            data["오늘r"]?.let { today -> require(weeks.any { it.request == today.jsonPrimitive.int }) }
            return weeks.sortedBy { it.start }
        } catch (e: Exception) {
            throw StructureChanged("시간표 게시 기간 목록을 읽을 수 없어요.", e)
        }
    }
}

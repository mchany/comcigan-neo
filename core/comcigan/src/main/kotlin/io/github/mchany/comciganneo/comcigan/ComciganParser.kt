/* Derived from hegelty/pycomcigan (MIT), Copyright (c) 2023 hegelty.
 * See THIRD_PARTY_NOTICES.md and LICENSES/pycomcigan-MIT.txt. */
package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Base64

class StructureChanged(message: String, cause: Throwable? = null) : Exception(message, cause)
class TimetableUnavailable : Exception("아직 게시된 시간표가 없어요.")

/** Page-issued request credential. Deliberately redacted from ServiceCodes and diagnostic strings. */
class HourRequestToken private constructor(private val value: String) {
    internal fun headerValue(): String = value
    override fun toString(): String = "HourRequestToken([redacted])"

    companion object {
        internal fun parse(value: String): HourRequestToken {
            if (!Regex("[0-9a-fA-F]{1,4096}").matches(value)) {
                throw StructureChanged("컴시간 페이지의 요청 토큰 형식이 변경됐어요.")
            }
            return HourRequestToken(value)
        }
    }
}

data class ServiceCodes(
    val searchRoute: String,
    val requestPrefix: String,
    val teacherField: String,
    val subjectField: String,
    val updatedField: String,
    val currentField: String,
    val originalField: String,
    val responseKeyHex: String? = null,
    val schoolCodeDivisor: Int? = null,
    val schoolCodeOffset: Int = 2,
    val responseFormat: String = "enc2",
    val encodedSchoolSearch: Boolean = false,
    val requestToken: HourRequestToken? = null,
) {
    val timetableRoute: String get() = searchRoute.substringBefore('?') + "?"
    fun searchPath(query: String): String {
        if (encodedSchoolSearch) {
            val token = searchRoute.substringAfter('?') + uriComponent(query) + "_$responseFormat"
            return timetableRoute + uriComponent(Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8)))
        }
        return searchRoute + URLEncoder.encode(query, "MS949") +
            if (responseKeyHex != null) "_$responseFormat" else ""
    }
    fun timetablePath(schoolCode: Int, week: Int): String {
        require(week in 0..1)
        val token = "${requestPrefix}_${schoolCode}_0_${week + 1}" +
            if (responseKeyHex != null) "_$responseFormat" else ""
        return timetableRoute + Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
    }

    // JavaScript encodeURIComponent uses UTF-8 and %20 for spaces, unlike form encoding.
    private fun uriComponent(value: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val n = byte.toInt() and 255
            val c = n.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") append(c)
            else append('%').append(hex[n / 16]).append(hex[n % 16])
        }
    }
}

object ComciganParser {
    fun serviceCodes(page: String): ServiceCodes = ComciganServicePage.codes(page)

    private fun objectFrom(text: String, codes: ServiceCodes? = null): JsonObject = try {
        val clean = ComciganResponseDecoder.decode(text, codes?.responseKeyHex)
        Json.parseToJsonElement(clean).jsonObject
    } catch (e: StructureChanged) { throw e
    } catch (e: Exception) { throw StructureChanged("시간표 응답을 읽을 수 없어요.", e) }

    fun schools(text: String, codes: ServiceCodes? = null): List<School> = try {
        val items = objectFrom(text, codes)["학교검색"]?.jsonArray
            ?: throw StructureChanged("학교 검색 응답 형식이 변경됐어요.")
        items.mapNotNull { item ->
            val row = item.jsonArray
            // This footer is not a school. Its selector would otherwise point at the message in row[2].
            if (row.size == 4 && row[0].jsonPrimitive.longOrNull == 0L && row[3].jsonPrimitive.intOrNull == 0) {
                return@mapNotNull null
            }
            val index = codes?.schoolCodeDivisor?.let { divisor ->
                val selector = row.firstOrNull()?.jsonPrimitive?.longOrNull
                    ?.takeIf { it >= 0 } ?: throw StructureChanged("학교 코드 위치를 읽을 수 없어요.")
                val selected = selector / divisor + codes.schoolCodeOffset
                if (selected < 0 || selected >= row.size) throw StructureChanged("학교 코드 위치를 읽을 수 없어요.")
                selected.toInt()
            } ?: 3
            val code = row.getOrNull(index)?.jsonPrimitive?.intOrNull
                ?: if (codes?.responseKeyHex != null) throw StructureChanged("학교 코드를 읽을 수 없어요.") else return@mapNotNull null
            if (code == 0) null else School(code, row[2].jsonPrimitive.content, row[1].jsonPrimitive.content)
        }.distinctBy { it.code }
    } catch (e: StructureChanged) { throw e }
      catch (e: Exception) { throw StructureChanged("학교 검색 응답 형식이 변경됐어요.", e) }

    fun timetable(text: String, codes: ServiceCodes, selection: ClassSelection, fetchedAt: Long): TimetableSnapshot {
        try {
            val data = objectFrom(text, codes)
            if (data.isEmpty() || data["시작일"] == null) throw TimetableUnavailable()
            val start = LocalDate.parse(data.getValue("시작일").jsonPrimitive.content)
            val counts = data.getValue("학급수").jsonArray.drop(1).map { it.jsonPrimitive.int }
            require(selection.grade <= counts.size && selection.classNumber <= counts[selection.grade - 1]) {
                "선택한 학급을 찾을 수 없어요. 학년과 반을 다시 선택해 주세요."
            }
            val teachers = data.getValue(codes.teacherField).jsonArray
            val subjects = data.getValue(codes.subjectField).jsonArray
            val current = data.getValue(codes.currentField).jsonArray[selection.grade].jsonArray[selection.classNumber].jsonArray
            val original = data.getValue(codes.originalField).jsonArray[selection.grade].jsonArray[selection.classNumber].jsonArray
            val divisor = data["분리"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
            require(divisor > 0)
            val notifications = data["변경알림"]?.jsonPrimitive?.content == "1"
            val starts = data["일과시간"]?.jsonArray?.mapNotNull {
                val value = it.jsonPrimitive.content
                Regex("""^(\d+)\((\d{1,2}:\d{2})""").find(value)?.let { match ->
                    match.groupValues[1].toInt() to match.groupValues[2].padStart(5, '0')
                }
            }?.toMap().orEmpty()
            fun row(array: JsonArray, day: Int) = array.getOrNull(day) as? JsonArray
            fun periodCount(row: JsonArray?): Int {
                if (row == null || row.isEmpty()) return 0
                val count = (row.firstOrNull() as? JsonPrimitive)?.intOrNull ?: (row.size - 1)
                if (count !in 0..64) throw StructureChanged("교시 수 형식이 변경됐어요.")
                return count
            }
            fun value(array: JsonArray?, period: Int): String =
                (array?.getOrNull(period) as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun packed(value: String): Long = when (value) {
                "", "null" -> 0
                else -> value.removePrefix(">").toLongOrNull()
                    ?: throw StructureChanged("수업 코드 형식이 변경됐어요.")
            }
            fun lesson(raw: String): Lesson? {
                val code = packed(raw)
                if (code == 0L) return null
                require(code > 0)
                val teacher = (code % 1000).toInt()
                val subject = ((code / 1000) % divisor).toInt()
                return Lesson(
                    subjects.getOrNull(subject)?.jsonPrimitive?.content
                        ?: throw StructureChanged("과목 코드 형식이 변경됐어요."),
                    teachers.getOrNull(teacher)?.jsonPrimitive?.content
                        ?: throw StructureChanged("교사 코드 형식이 변경됐어요."),
                )
            }
            val slots = buildList {
                // Preserve empty periods and use each day's published period count.
                val dayCount = maxOf(current.size, original.size) - 1
                for (day in 1..dayCount.coerceAtMost(7)) {
                    val now = row(current, day)
                    val before = row(original, day)
                    val periods = maxOf(periodCount(now), periodCount(before))
                    for (period in 1..periods) {
                        val raw = value(now, period)
                        val old = value(before, period)
                        val changed = if (notifications) raw.startsWith(">") else packed(raw) != packed(old)
                        add(LessonSlot(start.plusDays((day - 1).toLong()).toString(), period,
                            starts[period], lesson(raw), lesson(old), changed))
                    }
                }
            }
            return TimetableSnapshot(selection, start.toString(),
                data[codes.updatedField]?.jsonPrimitive?.contentOrNull,
                fetchedAt, counts, slots)
        } catch (e: TimetableUnavailable) { throw e }
          catch (e: StructureChanged) { throw e }
          catch (e: IllegalArgumentException) { throw StructureChanged(e.message ?: "시간표 형식이 변경됐어요.", e) }
          catch (e: Exception) { throw StructureChanged("시간표 응답 형식이 변경됐어요.", e) }
    }
}

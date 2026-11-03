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

data class ServiceCodes(
    val searchRoute: String,
    val requestPrefix: String,
    val teacherField: String,
    val subjectField: String,
    val updatedField: String,
    val currentField: String,
    val originalField: String,
) {
    val timetableRoute: String get() = searchRoute.substringBefore('?') + "?"
    /**
     * 역할: 학교 검색 API 경로를 구성한다.
     * 입력: query: String.
     * 출력: String 값.
     */
    fun searchPath(query: String): String = searchRoute + URLEncoder.encode(query, "MS949")
    /**
     * 역할: 시간표 API 경로를 구성한다.
     * 입력: schoolCode: Int, week: Int.
     * 출력: String 값.
     */
    fun timetablePath(schoolCode: Int, week: Int): String {
        require(week in 0..1)
        val token = "${requestPrefix}_${schoolCode}_0_${week + 1}"
        return timetableRoute + Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
    }
}

object ComciganParser {
    /**
     * 역할: 페이지에서 시간표 서비스 코드를 추출한다.
     * 입력: page: String.
     * 출력: ServiceCodes 값.
     */
    fun serviceCodes(page: String): ServiceCodes {
        /**
         * 역할: 정규식에서 지정한 값을 추출한다.
         * 입력: name: String, pattern: String.
         * 출력: String 값.
         */
        fun capture(name: String, pattern: String): String =
            Regex(pattern).find(page)?.groupValues?.get(1)
                ?: throw StructureChanged("컴시간 페이지의 $name 형식이 변경됐어요.")
        return ServiceCodes(
            capture("검색 경로", """\.(/\d+\?\d+l)"""),
            capture("요청 코드", """sc_data\(\s*'(\d+)_"""),
            "자료" + capture("교사", """성명\s*=\s*(?:Q성명\(\s*)?자료\.자료(\d+)"""),
            "자료" + capture("과목", """자료\.자료(\d+)\[sb\]"""),
            "자료" + capture("갱신 시각", """=\s*H시간표\.자료(\d+)"""),
            "자료" + capture("현재 시간표", """일일자료\s*=\s*Q자료\(\s*자료\.자료(\d+)"""),
            "자료" + capture("원본 시간표", """원자료\s*=\s*Q자료\(\s*자료\.자료(\d+)"""),
        )
    }

    /**
     * 역할: 문자열을 JSON 객체로 분석한다.
     * 입력: text: String.
     * 출력: JsonObject 값.
     */
    private fun objectFrom(text: String): JsonObject = try {
        val clean = text.trim('\u0000', '\r', '\n', ' ', '\t', '\uFEFF')
        Json.parseToJsonElement(clean).jsonObject
    } catch (e: Exception) { throw StructureChanged("시간표 응답을 읽을 수 없어요.", e) }

    /**
     * 역할: 학교 검색 응답을 학교 목록으로 변환한다.
     * 입력: text: String.
     * 출력: List<School> 값.
     */
    fun schools(text: String): List<School> = try {
        val items = objectFrom(text)["학교검색"]?.jsonArray
            ?: throw StructureChanged("학교 검색 응답 형식이 변경됐어요.")
        items.mapNotNull { item ->
            val row = item.jsonArray
            val code = row.getOrNull(3)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (code == 0) null else School(code, row[2].jsonPrimitive.content, row[1].jsonPrimitive.content)
        }.distinctBy { it.code }
    } catch (e: StructureChanged) { throw e }
      catch (e: Exception) { throw StructureChanged("학교 검색 응답 형식이 변경됐어요.", e) }

    /**
     * 역할: 시간표 응답을 수업 스냅샷으로 변환한다.
     * 입력: text: String, codes: ServiceCodes, selection: ClassSelection, fetchedAt: Long.
     * 출력: TimetableSnapshot 값.
     */
    fun timetable(text: String, codes: ServiceCodes, selection: ClassSelection, fetchedAt: Long): TimetableSnapshot {
        try {
            val data = objectFrom(text)
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
            /**
             * 역할: 배열에서 지정한 날짜의 행을 가져온다.
             * 입력: array: JsonArray, day: Int.
             * 출력: JsonArray? 값.
             */
            fun row(array: JsonArray, day: Int) = array.getOrNull(day) as? JsonArray
            /**
             * 역할: 시간표 행에 실제로 등록된 교시 수를 계산한다.
             * 입력: row: JsonArray?.
             * 출력: Int 값.
             */
            fun periodCount(row: JsonArray?): Int {
                if (row == null || row.isEmpty()) return 0
                val count = (row.firstOrNull() as? JsonPrimitive)?.intOrNull ?: (row.size - 1)
                if (count !in 0..64) throw StructureChanged("교시 수 형식이 변경됐어요.")
                return count
            }
            /**
             * 역할: 자료 배열에서 지정한 값을 읽는다.
             * 입력: array: JsonArray?, period: Int.
             * 출력: String 값.
             */
            fun value(array: JsonArray?, period: Int): String =
                (array?.getOrNull(period) as? JsonPrimitive)?.contentOrNull.orEmpty()
            /**
             * 역할: 컴시간 응답의 숫자 코드를 학년, 반, 교시 정보로 해독한다.
             * 입력: value: String.
             * 출력: Long 값.
             */
            fun packed(value: String): Long = when (value) {
                "", "null" -> 0
                else -> value.removePrefix(">").toLongOrNull()
                    ?: throw StructureChanged("수업 코드 형식이 변경됐어요.")
            }
            /**
             * 역할: 원시 수업 문자열을 수업 모델로 변환한다.
             * 입력: raw: String.
             * 출력: Lesson? 값.
             */
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

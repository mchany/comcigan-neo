package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.ClassSelection
import io.github.mchany.comciganneo.model.School
import io.github.mchany.comciganneo.model.schoolToday
import io.github.mchany.comciganneo.model.weekStart
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.time.LocalDate

/** Opt-in network health check. Offline regression tests run separately. */
/**
 * 역할: 실서버 파서 상태를 단계별로 검사하고 GitHub Actions 요약을 작성한다.
 * 입력: 선택적 학교명 인자와 GITHUB_STEP_SUMMARY 환경 변수.
 * 출력: 모든 검증이 통과하면 종료 코드 0, 검증 실패 시 예외로 종료한다.
 */
fun main(args: Array<String>) = runBlocking {
    val schoolName = args.firstOrNull() ?: "중일고등학교"
    val report = mutableListOf<Triple<String, String, String>>()
    val client = ComciganClient()
    lateinit var school: School

    /**
     * 역할: 검사 진행과 성공·실패 상태를 기록한다.
     * 입력: 단계 이름과 검증 결과를 만드는 suspend 작업.
     * 출력: 검증 성공 시 상세 결과 문자열을 반환하고, 실패 시 오류를 다시 전달한다.
     */
    suspend fun runCheck(name: String, action: suspend () -> String) {
        report += Triple(name, "RUNNING", "검사 중")
        val index = report.lastIndex
        println("Checking: $name")
        try {
            val detail = action()
            report[index] = Triple(name, "PASS", detail)
            println("PASS: $detail")
        } catch (error: Exception) {
            report[index] = Triple(name, "FAIL", failureDetail(error))
            println("FAIL: ${failureDetail(error)}")
            throw error
        }
    }

    try {
        runCheck("Service page, protocol and exact school search") {
            val matches = client.searchSchools(schoolName).filter { it.name == schoolName }
            check(matches.size == 1) { "Expected exactly one exact school match" }
            school = matches.single()
            check(school.code > 0) { "Expected a positive school code" }
            "정확히 일치하는 학교 1개, 유효한 학교 코드 확인 (${school.name}, ${school.region})"
        }

        runCheck("Empty school search") {
            val results = client.searchSchools("존재하지않는학교_파서상태검사_963852741")
            check(results.isEmpty()) { "Expected an empty school search result" }
            "존재하지 않는 학교 검색이 빈 목록으로 반환됨"
        }

        for (week in 0..1) {
            runCheck("Timetable week=$week") {
                val selection = ClassSelection(school, 1, 1)
                val snapshot = try {
                    client.fetch(selection, week)
                } catch (error: TimetableUnavailable) {
                    if (week != 1) throw error
                    return@runCheck "다음 주 시간표 미게시 응답을 정상 판별함"
                }

                val start = LocalDate.parse(snapshot.weekStart)
                check(start == weekStart(schoolToday(), week)) { "Timetable week did not match the requested week" }
                check(snapshot.selection == selection) { "Timetable selection did not match the request" }
                check(snapshot.classCounts.getOrNull(selection.grade - 1)?.let { it >= selection.classNumber } == true) {
                    "Invalid class-count structure"
                }
                check(snapshot.slots.isNotEmpty()) { "Timetable has no lesson slots" }
                check(snapshot.slots.all { slot ->
                    val date = LocalDate.parse(slot.date)
                    date in start..start.plusDays(6) && slot.period > 0
                }) { "Timetable contains an invalid date or period" }
                val lessons = snapshot.slots.mapNotNull { it.current }
                check(lessons.isNotEmpty()) { "No lessons parsed" }
                check(lessons.all { it.subject.isNotBlank() }) { "A parsed lesson has no subject" }
                "시작일 $start, 학급 구조 ${snapshot.classCounts.size}개 학년, 교시 ${snapshot.slots.size}개, 수업 ${lessons.size}개"
            }
        }
    } catch (error: Exception) {
        // Preserve the failed check in the summary without exposing response bodies or credentials.
    }

    val summary = buildString {
        appendLine("## Parser Health")
        appendLine()
        appendLine("Checked at ${Instant.now()}")
        appendLine()
        appendLine("| Check | Result | Detail |")
        appendLine("|---|---|---|")
        report.forEach { (name, status, detail) ->
            appendLine("| $name | $status | $detail |")
        }
    }
    print(summary)
    System.getenv("GITHUB_STEP_SUMMARY")?.let { path ->
        Files.writeString(Path.of(path), summary, UTF_8, CREATE, APPEND)
    }
    check(report.isNotEmpty() && report.all { it.second == "PASS" }) { "Parser health check failed" }
}

/**
 * 역할: 실서버 검사 오류를 민감한 응답 내용 없이 분류한다.
 * 입력: 검사 중 발생한 예외.
 * 출력: 로그와 GitHub Summary에 기록할 고정 오류 설명.
 */
private fun failureDetail(error: Exception): String = when (error) {
    is ComciganRateLimited -> "HTTP 429: 조회 제한 응답"
    is ComciganRequestRejected -> "HTTP 403: 요청 거부 또는 페이지 토큰 만료"
    is StructureChanged -> "서비스 프로토콜 또는 응답 형식 변경"
    is TimetableUnavailable -> "요청한 시간표가 게시되지 않음"
    is IOException -> "네트워크 또는 HTTP 요청 실패"
    else -> "응답 구조 또는 파서 검증 실패"
}

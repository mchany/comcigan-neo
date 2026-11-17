package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.ClassSelection
import io.github.mchany.comciganneo.model.School
import io.github.mchany.comciganneo.model.schoolToday
import io.github.mchany.comciganneo.model.TimetableSnapshot
import io.github.mchany.comciganneo.model.publishedWeekStarts
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.time.LocalDate

internal class HealthValidationFailure(
    message: String, val code: String = "VALIDATION_FAILED",
    val expectedStart: String? = null, val actualStart: String? = null,
) : IllegalStateException(message)

/** 미게시의 근거와 무관하게 날짜를 확인하며, 날짜 경계에서는 한 번만 재조회한다. */
internal suspend fun fetchHealthSnapshot(
    week: Int, today: () -> LocalDate,
    onDate: (LocalDate) -> Unit = {}, onRefetch: () -> Unit = {},
    fetch: suspend () -> TimetableSnapshot,
): Pair<LocalDate, TimetableSnapshot> {
    require(week in 0..1)
    repeat(2) { attempt ->
        val date = today()
        onDate(date)
        val outcome: Result<TimetableSnapshot> = try {
            Result.success(fetch())
        } catch (error: TimetableUnavailable) {
            Result.failure(error)
        }
        val after = today()
        if (date == after) return date to outcome.getOrThrow()
        if (attempt == 1) throw HealthValidationFailure(
            "DATE_BOUNDARY_UNSTABLE date=$date week=$week expected=$date actual=$after", "DATE_BOUNDARY_UNSTABLE")
        onRefetch()
    }
    error("Unreachable health retry state")
}

/** 검사 날짜에 선택한 기간과 응답 시작일을 비교한다. */
internal fun requirePublishedPeriod(snapshot: TimetableSnapshot, week: Int, date: LocalDate) {
    require(week in 0..1)
    val actual = LocalDate.parse(snapshot.weekStart)
    val expected = snapshot.publishedWeekStarts(date).getOrNull(week)?.let(LocalDate::parse)
        ?: throw HealthValidationFailure("EXPECTED_PERIOD_MISSING date=$date week=$week expected=none actual=$actual",
            "EXPECTED_PERIOD_MISSING", actualStart = actual.toString())
    if (actual != expected) throw HealthValidationFailure("PERIOD_MISMATCH date=$date week=$week expected=$expected actual=$actual",
        "PERIOD_MISMATCH", expected.toString(), actual.toString())
}

/** Opt-in network health check. Offline regression tests run separately. */
/**
 * 역할: 실서버 파서 상태를 단계별로 검사하고 GitHub Actions 요약을 작성한다.
 * 입력: 선택적 학교명 인자와 GITHUB_STEP_SUMMARY 환경 변수.
 * 출력: 모든 검증이 통과하면 종료 코드 0, 검증 실패 시 예외로 종료한다.
 */
fun main(args: Array<String>) = runBlocking {
    val client = ComciganClient()
    val report = runParserHealth(args.firstOrNull() ?: "중일고등학교",
        client::searchSchools, client::fetch, ::schoolToday, Instant::now) { kotlinx.coroutines.delay(it) }
    System.getenv("PARSER_HEALTH_RESULT")?.let { writeHealthResult(Path.of(it),report) }
    val summary = report.toMarkdown()
    print(summary)
    System.getenv("GITHUB_STEP_SUMMARY")?.let { path ->
        Files.writeString(Path.of(path),summary,UTF_8,CREATE,APPEND)
    }
    check(report.isSuccess) { "Parser health check failed; see structured results" }
}

/**
 * 역할: 실서버 검사 오류를 민감한 응답 내용 없이 분류한다.
 * 입력: 검사 중 발생한 예외.
 * 출력: 로그와 GitHub Summary에 기록할 고정 오류 설명.
 */
internal fun failureDetail(error: Exception): String = when (error) {
    is HealthValidationFailure -> error.message ?: "HEALTH_VALIDATION_FAILED"
    is ComciganRateLimited -> "HTTP 429: 조회 제한 응답"
    is ComciganRequestRejected -> "HTTP 403: 요청 거부 또는 페이지 토큰 만료"
    is StructureChanged -> "서비스 프로토콜 또는 응답 형식 변경"
    is TimetableUnavailable -> "요청한 시간표가 게시되지 않음"
    is IOException -> "네트워크 또는 HTTP 요청 실패"
    else -> "응답 구조 또는 파서 검증 실패"
}

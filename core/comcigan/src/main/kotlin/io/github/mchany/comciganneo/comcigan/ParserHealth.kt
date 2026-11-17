package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

internal enum class HealthStatus { HEALTHY, UNPUBLISHED, INDETERMINATE, FAIL, SKIPPED }
internal enum class HealthErrorType {
    NETWORK_ERROR, SERVER_ERROR, PROTOCOL_ERROR, PARSE_ERROR, VALIDATION_ERROR, UNKNOWN_ERROR, ENVIRONMENT_ERROR,
}
internal data class HealthAttempt(
    val number: Int, val status: HealthStatus, val errorType: HealthErrorType? = null, val code: String,
    val dateRefetches: Int = 0, val httpStatus: Int? = null,
)
internal data class HealthStep(
    val id: String, val status: HealthStatus, val errorType: HealthErrorType? = null, val code: String,
    val referenceDate: String, val week: Int? = null, val attempts: List<HealthAttempt> = emptyList(),
    val expectedStart: String? = null, val actualStart: String? = null, val httpStatus: Int? = null,
    val slotCount: Int? = null, val lessonCount: Int? = null,
)
internal data class HealthReport(val target: String, val checkedAt: String, val steps: List<HealthStep>) {
    val schemaVersion = 1
    val timezone = "Asia/Seoul"
    val isSuccess: Boolean get() = steps.map { it.id } == HEALTH_STEP_IDS &&
        steps.all { it.status == HealthStatus.HEALTHY || it.status == HealthStatus.UNPUBLISHED }
    val overall: HealthStatus get() = when {
        steps.any { it.status == HealthStatus.FAIL } -> HealthStatus.FAIL
        !isSuccess -> HealthStatus.INDETERMINATE
        steps.any { it.status == HealthStatus.UNPUBLISHED } -> HealthStatus.UNPUBLISHED
        else -> HealthStatus.HEALTHY
    }
}
internal val HEALTH_STEP_IDS = listOf("exact_search", "empty_search", "timetable_0", "timetable_1")

private data class HealthOutcome(
    val status: HealthStatus = HealthStatus.HEALTHY, val code: String,
    val errorType: HealthErrorType? = null, val httpStatus: Int? = null,
    val expectedStart: String? = null, val actualStart: String? = null,
    val slotCount: Int? = null, val lessonCount: Int? = null,
)

/** Classifies by typed origin, never by arbitrary exception text. */
private fun healthFailure(error: Exception, timetable: Boolean): HealthOutcome = when (error) {
    is TimetableUnavailable -> if (!timetable) HealthOutcome(HealthStatus.FAIL, "UNKNOWN_FAILURE", HealthErrorType.UNKNOWN_ERROR)
        else if (error.evidence == UnpublishedEvidence.CATALOG_ABSENT) HealthOutcome(HealthStatus.UNPUBLISHED, "CATALOG_ABSENT")
        else HealthOutcome(HealthStatus.INDETERMINATE, "AMBIGUOUS_UNPUBLISHED", HealthErrorType.UNKNOWN_ERROR)
    is HealthValidationFailure -> HealthOutcome(HealthStatus.FAIL, error.code, HealthErrorType.VALIDATION_ERROR,
        expectedStart=error.expectedStart, actualStart=error.actualStart)
    is TimetablePeriodChanged -> HealthOutcome(HealthStatus.FAIL, "PERIOD_CHANGED", HealthErrorType.VALIDATION_ERROR)
    is ComciganRateLimited -> HealthOutcome(HealthStatus.FAIL, "HTTP_ERROR", HealthErrorType.SERVER_ERROR, 429)
    is ComciganRequestRejected -> HealthOutcome(HealthStatus.FAIL, "HTTP_ERROR", HealthErrorType.SERVER_ERROR, 403)
    is ComciganHttpException -> HealthOutcome(HealthStatus.FAIL, "HTTP_ERROR", HealthErrorType.SERVER_ERROR, error.statusCode)
    is ComciganResponseException -> HealthOutcome(HealthStatus.FAIL, "RESPONSE_PARSE_FAILED", HealthErrorType.PARSE_ERROR)
    is StructureChanged -> if (error.source == StructureSource.PARSE)
        HealthOutcome(HealthStatus.FAIL, "RESPONSE_PARSE_FAILED", HealthErrorType.PARSE_ERROR)
        else HealthOutcome(HealthStatus.FAIL, "PROTOCOL_CHANGED", HealthErrorType.PROTOCOL_ERROR)
    is IOException -> HealthOutcome(HealthStatus.FAIL, "NETWORK_ERROR", HealthErrorType.NETWORK_ERROR)
    else -> HealthOutcome(HealthStatus.FAIL, "UNKNOWN_FAILURE", HealthErrorType.UNKNOWN_ERROR)
}

private fun requireHealth(condition: Boolean, code: String) {
    if (!condition) throw HealthValidationFailure(code, code)
}

private fun validateSnapshot(snapshot: TimetableSnapshot, selection: ClassSelection, week: Int, date: LocalDate): HealthOutcome {
    val start = try { LocalDate.parse(snapshot.weekStart) }
        catch (_: java.time.DateTimeException) { throw HealthValidationFailure("INVALID_START", "INVALID_START") }
    requirePublishedPeriod(snapshot, week, date)
    requireHealth(snapshot.selection == selection, "SELECTION_MISMATCH")
    requireHealth(snapshot.classCounts.getOrNull(selection.grade-1)?.let { it >= selection.classNumber } == true,
        "INVALID_CLASS_COUNTS")
    requireHealth(snapshot.slots.all { slot ->
        val slotDate = try { LocalDate.parse(slot.date) } catch (_: java.time.DateTimeException) { null }
        slotDate != null && slotDate in start..start.plusDays(6) && slot.period > 0
    }, "INVALID_SLOT")
    val lessons = snapshot.slots.mapNotNull { it.current }
    requireHealth(lessons.all { it.subject.isNotBlank() }, "BLANK_SUBJECT")
    val code = when {
        snapshot.slots.isEmpty() -> "NO_SLOTS"
        lessons.isEmpty() -> "NO_LESSONS"
        else -> "TIMETABLE_OK"
    }
    return HealthOutcome(
        status=if (code == "TIMETABLE_OK") HealthStatus.HEALTHY else HealthStatus.INDETERMINATE,
        code=code, errorType=if (code == "TIMETABLE_OK") null else HealthErrorType.VALIDATION_ERROR,
        expectedStart=start.toString(), actualStart=start.toString(), slotCount=snapshot.slots.size, lessonCount=lessons.size)
}

/** External calls and the clock are injectable; ordinary unit tests never contact the service. */
internal suspend fun runParserHealth(
    schoolName: String, search: suspend (String) -> List<School>,
    fetch: suspend (ClassSelection, Int) -> TimetableSnapshot, today: () -> LocalDate,
    now: () -> Instant, pause: suspend (Long) -> Unit,
): HealthReport {
    val checkedAt = now().toString()
    val steps = mutableListOf<HealthStep>()
    var school: School? = null

    suspend fun runStep(id: String, week: Int? = null, action: suspend (AttemptClock) -> HealthOutcome) {
        val attempts = mutableListOf<HealthAttempt>()
        repeat(2) { index ->
            val clock = AttemptClock(today())
            val outcome = try { action(clock) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { healthFailure(error, week != null) }
            attempts += HealthAttempt(index+1, outcome.status, outcome.errorType, outcome.code, clock.refetches, outcome.httpStatus)
            val transient = outcome.errorType == HealthErrorType.NETWORK_ERROR ||
                (outcome.errorType == HealthErrorType.SERVER_ERROR && outcome.httpStatus in listOf(500,502,503,504))
            if (index == 0 && transient) {
                pause(1000)
            } else {
                steps += HealthStep(id, outcome.status, outcome.errorType, outcome.code, clock.date.toString(), week,
                    attempts.toList(), outcome.expectedStart, outcome.actualStart, outcome.httpStatus,
                    outcome.slotCount, outcome.lessonCount)
                return
            }
        }
    }

    runStep("exact_search") {
        val matches = search(schoolName).filter { it.name == schoolName }
        requireHealth(matches.size == 1, "EXACT_MATCH_COUNT")
        val match = matches.single()
        requireHealth(match.code > 0, "INVALID_SCHOOL_CODE")
        school = match
        HealthOutcome(code="SEARCH_OK")
    }
    runStep("empty_search") {
        requireHealth(search("존재하지않는학교_파서상태검사_963852741").isEmpty(), "NONEMPTY_SEARCH_RESULT")
        HealthOutcome(code="EMPTY_SEARCH_OK")
    }
    for (week in 0..1) {
        val selected = school
        if (selected == null) {
            steps += HealthStep("timetable_$week",HealthStatus.SKIPPED,code="DEPENDENCY_FAILED",
                referenceDate=steps.first().referenceDate,week=week)
            continue
        }
        runStep("timetable_$week", week) { clock ->
            val selection = ClassSelection(selected,1,1)
            var firstRead = true
            val (date, snapshot) = fetchHealthSnapshot(week,
                today={ if (firstRead) { firstRead=false; clock.date } else today() },
                onDate={ clock.date=it }, onRefetch={ clock.refetches++ }) { fetch(selection,week) }
            validateSnapshot(snapshot,selection,week,date)
        }
    }
    return HealthReport(schoolName,checkedAt,steps)
}
private class AttemptClock(var date: LocalDate, var refetches: Int = 0)

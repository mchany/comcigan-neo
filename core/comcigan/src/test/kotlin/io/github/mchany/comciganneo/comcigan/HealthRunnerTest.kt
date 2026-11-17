package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.ArrayDeque
import kotlin.test.*

class HealthRunnerTest {
    @Test fun kotlinWireFormatMatchesSharedNodeContract() = runBlocking {
        val expected = javaClass.getResourceAsStream("/health-report-v1.json")!!.bufferedReader().use { it.readText() }
        assertEquals(Json.parseToJsonElement(expected),Json.parseToJsonElement(report().toJson()))
    }
    @Test fun targetCannotInjectMarkdownOrMentions() = runBlocking {
        val target="school|@someone<script>\n`"
        val r=runParserHealth(target,
            { if(it==target) listOf(school.copy(name=target)) else emptyList() },
            { selected,w->snapshot(w).copy(selection=selected) },{ date },
            { Instant.parse("2026-11-16T03:00:00Z") },{})
        assertTrue(r.isSuccess)
        val text=r.toMarkdown()
        assertFalse(text.contains("@someone"));assertFalse(text.contains("<script>"))
        assertFalse(text.contains("school|"))
    }
    private val date = LocalDate.parse("2026-11-16")
    private val school = School(1, "school", "region")
    private val selection = ClassSelection(school, 1, 1)
    private val periods = listOf(PublishedTimetableWeek(7, "2026-11-16"), PublishedTimetableWeek(19, "2026-11-23"))
    private fun snapshot(week: Int) = TimetableSnapshot(selection, periods[week].start, fetchedAt=0,
        classCounts=listOf(1), slots=listOf(LessonSlot(periods[week].start, 1, "09:00", Lesson("subject", "teacher"), null, false)),
        publishedWeeks=periods)
    private suspend fun report(
        search: suspend (String) -> List<School> = { if (it=="school") listOf(school) else emptyList() },
        fetch: suspend (ClassSelection, Int) -> TimetableSnapshot = { _, w -> snapshot(w) },
        today: () -> LocalDate = { date },
        pause: suspend (Long) -> Unit = { assertEquals(1000L,it) },
    ) = runParserHealth("school",search,fetch,today,{ Instant.parse("2026-11-16T03:00:00Z") },pause)

    @Test fun healthyReportIncludesAllFourChecksAndPublishedDates() = runBlocking {
        val r=report()
        assertTrue(r.isSuccess)
        assertEquals(HealthStatus.HEALTHY,r.overall)
        assertEquals(listOf("exact_search","empty_search","timetable_0","timetable_1"),r.steps.map { it.id })
        assertTrue(r.steps.all { it.status==HealthStatus.HEALTHY && it.attempts.size==1 })
        assertEquals("2026-11-23",r.steps.last().actualStart)
    }
    @Test fun onlyCatalogEvidenceProvesUnpublishedForEitherPeriod() = runBlocking {
        val r=report(fetch={ _,_->throw TimetableUnavailable(UnpublishedEvidence.CATALOG_ABSENT) })
        assertTrue(r.isSuccess)
        assertEquals(HealthStatus.UNPUBLISHED,r.overall)
        assertTrue(r.steps.takeLast(2).all { it.status==HealthStatus.UNPUBLISHED })
        val ambiguous=report(fetch={ _,_->throw TimetableUnavailable() })
        assertFalse(ambiguous.isSuccess)
        assertEquals(HealthStatus.INDETERMINATE,ambiguous.overall)
    }
    @Test fun missingSlotsOrLessonsAreIndeterminateInsteadOfUnpublished() = runBlocking {
        for (emptySlots in listOf(true,false)) {
            val r=report(fetch={ _,w->snapshot(w).let { if(emptySlots) it.copy(slots=emptyList()) else it.copy(slots=it.slots.map { s->s.copy(current=null) }) } })
            assertEquals(HealthStatus.INDETERMINATE,r.overall)
            assertTrue(r.steps.takeLast(2).all { it.status==HealthStatus.INDETERMINATE && it.errorType==HealthErrorType.VALIDATION_ERROR })
        }
    }
    @Test fun independentChecksContinueAndMissingSchoolSkipsOnlyDependentChecks() = runBlocking {
        val queries=mutableListOf<String>()
        val r=report(search={ queries+=it; if(it=="school") throw UnknownHostException("secret") else emptyList() })
        assertEquals(3,queries.size)
        assertEquals(HealthStatus.HEALTHY,r.steps[1].status)
        assertTrue(r.steps.takeLast(2).all { it.status==HealthStatus.SKIPPED && it.attempts.isEmpty() })
        assertFalse(r.isSuccess)
    }
    @Test fun firstPeriodFailureDoesNotPreventSecondPeriodCheck() = runBlocking {
        val r=report(fetch={ _,w-> if(w==0) throw StructureChanged("secret",source=StructureSource.PARSE) else snapshot(w) })
        assertEquals(HealthStatus.FAIL,r.steps[2].status)
        assertEquals(HealthStatus.HEALTHY,r.steps[3].status)
    }
    @Test fun transientNetworkFailureRecordsBothAttemptsAndDoesNotFailOverall() = runBlocking {
        var calls=0;var pauses=0
        val r=report(search={ if(it!="school") emptyList() else { if(++calls==1) throw SocketTimeoutException("token-secret"); listOf(school) } },pause={ pauses++;assertEquals(1000,it.toInt()) })
        assertTrue(r.isSuccess);assertEquals(1,pauses);assertEquals(2,calls)
        assertEquals(listOf(HealthStatus.FAIL,HealthStatus.HEALTHY),r.steps[0].attempts.map { it.status })
        assertEquals(HealthErrorType.NETWORK_ERROR,r.steps[0].attempts[0].errorType)
    }
    @Test fun persistentNetworkErrorStopsAfterTwoAttempts() = runBlocking {
        var calls=0
        val r=report(fetch={ _,_->calls++;throw IOException("secret") })
        assertEquals(4,calls)
        assertTrue(r.steps.takeLast(2).all { it.attempts.size==2 && it.errorType==HealthErrorType.NETWORK_ERROR })
        assertFalse(r.isSuccess)
    }
    @Test fun onlySelectedServerStatusesAreRetried() = runBlocking {
        for (status in listOf(400,401,404,408,500,501,502,503,504)) {
            var calls=0
            val r=report(fetch={ _,_->calls++;throw ComciganHttpException(status) })
            assertEquals(if(status in listOf(500,502,503,504)) 4 else 2,calls)
            assertTrue(r.steps.takeLast(2).all { it.errorType==HealthErrorType.SERVER_ERROR && it.httpStatus==status })
            val json=Json.parseToJsonElement(r.toJson()).jsonObject
            assertEquals(status,json.getValue("steps").jsonArray[2].jsonObject.getValue("attempts").jsonArray[0].jsonObject.getValue("httpStatus").jsonPrimitive.int)
        }
    }
    @Test fun protocolParseValidationUnknownAndRequestErrorsAreNotRetried() = runBlocking {
        val cases=listOf(
            StructureChanged("secret") to HealthErrorType.PROTOCOL_ERROR,
            StructureChanged("secret",source=StructureSource.PARSE) to HealthErrorType.PARSE_ERROR,
            ComciganResponseException("secret") to HealthErrorType.PARSE_ERROR,
            ComciganRequestRejected() to HealthErrorType.SERVER_ERROR,
            ComciganRateLimited() to HealthErrorType.SERVER_ERROR,
            TimetablePeriodChanged() to HealthErrorType.VALIDATION_ERROR,
            IllegalStateException("secret") to HealthErrorType.UNKNOWN_ERROR,
        )
        for ((error,type) in cases) {
            var calls=0
            val r=report(fetch={ _,_->calls++;throw error })
            assertEquals(2,calls)
            assertEquals(type,r.steps[2].errorType)
        }
    }
    @Test fun cancellationPropagatesWithoutRetryOrFakeReport() = runBlocking {
        val cancel=CancellationException("secret"); var calls=0
        assertSame(cancel,assertFailsWith<CancellationException> { report(fetch={ _,_->calls++;throw cancel }) })
        assertEquals(1,calls)
    }
    @Test fun cancellationDuringBackoffAlsoPropagates() = runBlocking {
        val cancel=CancellationException("secret")
        assertSame(cancel,assertFailsWith<CancellationException> { report(fetch={ _,_->throw IOException() },pause={ throw cancel }) })
    }
    @Test fun wrongPeriodSelectionClassesSlotsAndSubjectsRemainFailures() = runBlocking {
        val changes: List<(TimetableSnapshot)->TimetableSnapshot> = listOf(
            { it.copy(weekStart="2026-11-30") },
            { it.copy(selection=selection.copy(classNumber=2)) },
            { it.copy(classCounts=emptyList()) },
            { it.copy(slots=it.slots.map { s->s.copy(period=0) }) },
            { it.copy(slots=it.slots.map { s->s.copy(date="2026-11-30") }) },
            { it.copy(slots=it.slots.map { s->s.copy(current=Lesson(" ", "teacher")) }) },
        )
        for (change in changes) {
            val r=report(fetch={ _,w->change(snapshot(w)) })
            assertTrue(r.steps.takeLast(2).all { it.status==HealthStatus.FAIL && it.errorType==HealthErrorType.VALIDATION_ERROR })
        }
    }
    @Test fun emptySearchAndExactSchoolAssertionsAreValidationErrors() = runBlocking {
        val r=report(search={ listOf(school,school) })
        assertEquals(HealthErrorType.VALIDATION_ERROR,r.steps[0].errorType)
        assertEquals(HealthErrorType.VALIDATION_ERROR,r.steps[1].errorType)
    }
    @Test fun unavailableOutsideTimetableIsNeverHealthy() = runBlocking {
        val r=report(search={ throw TimetableUnavailable(UnpublishedEvidence.CATALOG_ABSENT) })
        assertFalse(r.isSuccess)
        assertEquals(HealthStatus.FAIL,r.steps[0].status)
    }
    @Test fun timezoneIsKoreanAndCrossYearPublishedDatesAreRespected() = runBlocking {
        val starts=listOf("2027-01-04","2027-01-11")
        val r=report(today={ LocalDate.parse("2027-01-03") },fetch={ _,w->snapshot(w).copy(
            weekStart=starts[w],publishedWeeks=starts.mapIndexed { i,s->PublishedTimetableWeek(i+7,s) },
            slots=snapshot(w).slots.map { it.copy(date=starts[w]) }) })
        assertTrue(r.isSuccess);assertEquals("Asia/Seoul",r.timezone)
        assertEquals("2027-01-03",r.steps[2].referenceDate)
    }
    @Test fun midnightRefetchIsRecordedSeparatelyFromHealthAttempts() = runBlocking {
        // exact, empty, week0-before/after, retry-before/after, week1-before/after
        val dates=ArrayDeque(listOf(date,date,date,date.plusDays(1),date.plusDays(1),date.plusDays(1),date.plusDays(1),date.plusDays(1)))
        var calls=0
        val r=report(today={ dates.removeFirst() },fetch={ _,w->calls++;snapshot(w) })
        assertTrue(r.isSuccess);assertEquals(3,calls)
        assertEquals(1,r.steps[2].attempts.single().dateRefetches)
        assertEquals("2026-11-17",r.steps[2].referenceDate)
    }
    @Test fun ambiguousFirstPeriodAlsoChecksDateBoundary() = runBlocking {
        val dates=ArrayDeque(listOf(date,date,date,date.plusDays(1),date.plusDays(1),date.plusDays(1),date.plusDays(1),date.plusDays(1)))
        var calls=0
        val r=report(today={ dates.removeFirst() },fetch={ _,_->calls++;throw TimetableUnavailable() })
        assertEquals(3,calls);assertEquals(1,r.steps[2].attempts.single().dateRefetches)
        assertEquals(HealthStatus.INDETERMINATE,r.steps[2].status)
    }
    @Test fun repeatedDateChangesAreNotHiddenAsUnpublished() = runBlocking {
        var clock=0
        val r=report(today={ date.plusDays((clock++).toLong()) },fetch={ _,_->throw TimetableUnavailable(UnpublishedEvidence.CATALOG_ABSENT) })
        assertFalse(r.isSuccess)
        assertEquals("DATE_BOUNDARY_UNSTABLE",r.steps[2].code)
        assertEquals(2,r.steps[2].attempts.single().dateRefetches+1)
    }
    @Test fun jsonAndMarkdownContainStructuredControlledDiagnostics() = runBlocking {
        val r=report(fetch={ _,_->throw IOException("token-secret response-secret") })
        val json=r.toJson();val parsed=Json.parseToJsonElement(json).jsonObject
        assertEquals(1,parsed.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(4,parsed.getValue("steps").jsonArray.size)
        assertTrue(json.contains("NETWORK_ERROR"));assertFalse(json.contains("secret"))
        assertTrue(r.toMarkdown().contains("NETWORK_ERROR"))
        assertTrue(r.toMarkdown().contains("2026-11-16"))
        assertFalse(r.toMarkdown().contains("secret"))
    }
    @Test fun reportFileContainsCompleteJsonAndTemporaryFileIsRemoved() = runBlocking {
        val dir=Files.createTempDirectory("health-result")
        try {
            val p=dir.resolve("result.json");val r=report()
            writeHealthResult(p,r)
            assertEquals(r.toJson(),Files.readString(p))
            assertEquals(listOf(p),Files.list(dir).use { it.toList() })
            writeHealthResult(p,r)
            assertEquals(r.toJson(),Files.readString(p))
        } finally { Files.list(dir).use { it.forEach(Files::delete) };Files.delete(dir) }
    }
}

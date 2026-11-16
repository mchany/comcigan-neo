package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import java.time.LocalDate
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LiveCheckTest {
    private val sunday = LocalDate.parse("2026-11-15")
    private val periods = listOf(PublishedTimetableWeek(1,"2026-11-16"), PublishedTimetableWeek(2,"2026-11-23"))
    private fun snapshot(start: String = "2026-11-16", weeks: List<PublishedTimetableWeek> = periods) =
        TimetableSnapshot(ClassSelection(School(1,"school-secret","region-secret"),1,1),start,
            fetchedAt=0,classCounts=listOf(1),slots=emptyList(),publishedWeeks=weeks)

    @Test fun sundayEarlyPublicationPasses() {
        requirePublishedPeriod(snapshot(),0,sunday)
    }

    @Test fun mondayPublicationPasses() {
        requirePublishedPeriod(snapshot(),0,LocalDate.parse("2026-11-16"))
    }

    @Test fun anotherPeriodIsRejected() {
        val error = assertFailsWith<HealthValidationFailure> { requirePublishedPeriod(snapshot(),1,sunday) }
        assertEquals("PERIOD_MISMATCH date=2026-11-15 week=1 expected=2026-11-23 actual=2026-11-16",error.message)
    }

    @Test fun missingSecondPeriodIsRejected() {
        val error = assertFailsWith<HealthValidationFailure> { requirePublishedPeriod(snapshot(weeks=periods.take(1)),1,sunday) }
        assertEquals("EXPECTED_PERIOD_MISSING date=2026-11-15 week=1 expected=none actual=2026-11-16",error.message)
    }

    @Test fun legacyMetadataUsesCalendarWeeks() {
        requirePublishedPeriod(snapshot("2026-11-09",emptyList()),0,sunday)
        assertFailsWith<HealthValidationFailure> { requirePublishedPeriod(snapshot(weeks=emptyList()),0,sunday) }
    }

    @Test fun publishedPeriodCrossesYearBoundary() {
        val weeks = listOf(PublishedTimetableWeek(1,"2027-01-04"),PublishedTimetableWeek(2,"2027-01-11"))
        requirePublishedPeriod(snapshot("2027-01-04",weeks),0,LocalDate.parse("2027-01-03"))
    }

    @Test fun unorderedNonconsecutiveSelectorsFollowDates() {
        val weeks = listOf(PublishedTimetableWeek(19,"2026-11-23"),PublishedTimetableWeek(7,"2026-11-16"))
        requirePublishedPeriod(snapshot("2026-11-16",weeks),0,sunday)
        requirePublishedPeriod(snapshot("2026-11-23",weeks),1,sunday)
    }

    @Test fun invalidPeriodIndicesAreRejected() {
        for (week in listOf(-1,2)) assertFailsWith<IllegalArgumentException> { requirePublishedPeriod(snapshot(),week,sunday) }
    }

    @Test fun stableDateFetchesOnlyOnce() = runBlocking {
        var calls = 0
        var clockReads = 0
        val data = snapshot()
        val result = fetchHealthSnapshot(0,{ clockReads++; sunday }) { calls++; data }
        assertEquals(sunday,result.first)
        assertSame(data,result.second)
        assertEquals(1,calls)
        assertEquals(2,clockReads)
    }

    @Test fun midnightRefetchesWithNewDate() = runBlocking {
        val monday = LocalDate.parse("2026-11-16")
        val dates = ArrayDeque(listOf(sunday,monday,monday,monday))
        var calls = 0
        val stale = snapshot("2026-11-09")
        val current = snapshot()
        val result = fetchHealthSnapshot(0,{ dates.removeFirst() }) { if (++calls==1) stale else current }
        assertEquals(2,calls)
        assertEquals(monday,result.first)
        assertSame(current,result.second)
        requirePublishedPeriod(result.second,0,result.first)
    }

    @Test fun repeatedDateChangesStopAfterTwoFetches() = runBlocking {
        val dates = ArrayDeque(listOf(sunday,sunday.plusDays(1),sunday.plusDays(1),sunday.plusDays(2)))
        var calls = 0
        val error = assertFailsWith<HealthValidationFailure> {
            fetchHealthSnapshot(1,{ dates.removeFirst() }) { calls++; snapshot("2026-11-23") }
        }
        assertEquals(2,calls)
        assertEquals("DATE_BOUNDARY_UNSTABLE date=2026-11-16 week=1 expected=2026-11-16 actual=2026-11-17",failureDetail(error))
    }

    @Test fun fetchFailuresAreNotRetriedOrReplaced() = runBlocking {
        for (error in listOf(IOException("token-secret"),StructureChanged("key-secret"),CancellationException("cancel-secret"))) {
            var calls = 0
            val thrown = assertFailsWith<Exception> { fetchHealthSnapshot(0,{ sunday }) { calls++; throw error } }
            assertSame(error,thrown)
            assertEquals(1,calls)
        }
    }

    @Test fun invalidFetchIndexDoesNotInvokeDependencies() = runBlocking {
        for (week in listOf(-1,2)) assertFailsWith<IllegalArgumentException> {
            fetchHealthSnapshot(week,{ fail("Unexpected clock read") }) { fail("Unexpected fetch") }
        }
    }

    @Test fun diagnosticsOnlyExposeControlledDatesAndIndices() {
        val error = assertFailsWith<HealthValidationFailure> { requirePublishedPeriod(snapshot(),1,sunday) }
        assertEquals("PERIOD_MISMATCH date=2026-11-15 week=1 expected=2026-11-23 actual=2026-11-16",failureDetail(error))
        val missing = assertFailsWith<HealthValidationFailure> { requirePublishedPeriod(snapshot(weeks=periods.take(1)),1,sunday) }
        assertEquals("EXPECTED_PERIOD_MISSING date=2026-11-15 week=1 expected=none actual=2026-11-16",failureDetail(missing))
        assertFalse(failureDetail(error).contains("secret"))
        assertEquals("응답 구조 또는 파서 검증 실패",failureDetail(IllegalStateException("token-secret key-secret teacher-secret")))
        assertEquals("HTTP 429: 조회 제한 응답",failureDetail(ComciganRateLimited()))
        assertEquals("HTTP 403: 요청 거부 또는 페이지 토큰 만료",failureDetail(ComciganRequestRejected()))
        assertEquals("서비스 프로토콜 또는 응답 형식 변경",failureDetail(StructureChanged("secret")))
        assertEquals("요청한 시간표가 게시되지 않음",failureDetail(TimetableUnavailable()))
        assertEquals("네트워크 또는 HTTP 요청 실패",failureDetail(IOException("secret")))
    }
}

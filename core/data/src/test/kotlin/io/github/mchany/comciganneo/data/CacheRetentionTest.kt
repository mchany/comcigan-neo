package io.github.mchany.comciganneo.data

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheRetentionTest {
    /**
     * 역할: 한국 시간대의 테스트 시각을 epoch 밀리초로 변환한다.
     * 입력: 시간대가 포함된 ISO 시각 문자열.
     * 출력: epoch 밀리초 값.
     */
    private fun millis(value: String): Long = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    /**
     * 역할: 기존 캐시와 동일한 저장 시각 필드를 가진 JSON을 만든다.
     * 입력: 조회·저장 시각 문자열.
     * 출력: fetchedAt 필드가 들어 있는 JSON 문자열.
     */
    private fun snapshot(saved: String): String = """{"fetchedAt":${millis(saved)},"weekStart":"2020-01-06"}"""

    /**
     * 역할: 정확한 3개월 경계와 최근 자료 보존 여부를 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 만료 판정이 다르면 테스트가 실패한다.
     */
    @Test fun expiresAtThreeMonthsFromSavedTime() {
        val data = snapshot("2026-08-08T09:30:00+09:00")
        val expiry = millis("2026-11-08T09:30:00+09:00")
        assertFalse(isCacheExpired(data, expiry - 1))
        assertTrue(isCacheExpired(data, expiry))
        assertTrue(isCacheExpired(data, expiry + 1))
        assertFalse(isCacheExpired(snapshot("2026-11-08T09:30:00+09:00"), expiry))
    }

    /**
     * 역할: 월말과 윤년을 지나도 각 저장일에 3개월을 더해 만료일을 계산하는지 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 월말 또는 윤년 처리가 다르면 테스트가 실패한다.
     */
    @Test fun handlesMonthEndsLeapYearsAndYearRollover() {
        for ((saved, expires) in listOf(
            "2026-08-31T12:00:00+09:00" to "2026-11-30T12:00:00+09:00",
            "2023-11-30T12:00:00+09:00" to "2024-02-29T12:00:00+09:00",
            "2026-11-30T12:00:00+09:00" to "2027-02-28T12:00:00+09:00",
        )) {
            val expiry = millis(expires)
            assertFalse(isCacheExpired(snapshot(saved), expiry - 1))
            assertTrue(isCacheExpired(snapshot(saved), expiry))
        }
    }

    /**
     * 역할: UTC 날짜가 달라도 한국 시간의 저장일을 기준으로 만료일을 계산하는지 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 한국 시간대 기준을 따르지 않으면 테스트가 실패한다.
     */
    @Test fun usesKoreanCalendarAndKeepsFutureTimestamps() {
        val data = snapshot("2026-01-31T16:00:00Z")
        assertFalse(isCacheExpired(data, millis("2026-04-30T00:59:59+09:00")))
        assertFalse(isCacheExpired(data, millis("2026-05-01T00:59:59+09:00")))
        assertTrue(isCacheExpired(data, millis("2026-05-01T01:00:00+09:00")))
        assertFalse(isCacheExpired(snapshot("2027-01-01T00:00:00+09:00"), millis("2026-11-08T12:00:00+09:00")))
    }

    /**
     * 역할: 자료의 주차 대신 저장 시각을 사용하고 나이를 알 수 없는 자료는 삭제하지 않는지 확인한다.
     * 입력: 없음.
     * 출력: 없음(Unit)이며 알 수 없는 시각을 만료로 처리하면 테스트가 실패한다.
     */
    @Test fun ignoresTimetableDatesAndUnreadableTimestamps() {
        val now = millis("2026-11-08T12:00:00+09:00")
        assertFalse(isCacheExpired(snapshot("2026-11-01T00:00:00+09:00"), now))
        for (data in listOf("not JSON", "{}", "[]", """{"fetchedAt":null}""", """{"fetchedAt":"invalid"}""")) {
            assertFalse(isCacheExpired(data, now))
        }
    }
}

package io.github.mchany.comciganneo.model

import java.time.LocalDate
import kotlin.test.*

class WeekStartTest {
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun `Sunday belongs to preceding week and crosses year correctly`() {
        assertEquals("2026-12-28", weekStart(LocalDate.parse("2027-01-03")).toString())
        assertEquals("2027-01-04", weekStart(LocalDate.parse("2027-01-03"), 1).toString())
    }
}

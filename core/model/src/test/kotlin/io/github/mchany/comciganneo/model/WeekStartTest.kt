package io.github.mchany.comciganneo.model

import java.time.LocalDate
import kotlin.test.*

class WeekStartTest {
    @Test fun `Sunday belongs to preceding week and crosses year correctly`() {
        assertEquals("2026-12-28", weekStart(LocalDate.parse("2027-01-03")).toString())
        assertEquals("2027-01-04", weekStart(LocalDate.parse("2027-01-03"), 1).toString())
    }
}

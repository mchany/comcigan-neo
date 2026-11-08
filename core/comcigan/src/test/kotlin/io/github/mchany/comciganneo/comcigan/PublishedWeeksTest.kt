package io.github.mchany.comciganneo.comcigan

import java.time.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*

class PublishedWeeksTest {
    private fun parse(rows: String, start: String = "2026-11-09", today: Int = 1) =
        ComciganPublishedWeeks.parse(Json.parseToJsonElement("""{"일자자료":$rows,"오늘r":$today}""").jsonObject,LocalDate.parse(start))

    @Test fun `sorts catalog by date while preserving actual request ids`() {
        val weeks = parse("""[[9,"2026-11-16 ~ 2026-11-21"],[1,"26-11-09 ~ 26-11-14"]]""")
        assertEquals(listOf(1,9),weeks.map { it.request })
        assertEquals(listOf("2026-11-09","2026-11-16"),weeks.map { it.start })
    }

    @Test fun `resolves two digit years across year and century boundaries`() {
        assertEquals(listOf("2026-12-28","2027-01-04"),parse(
            """[[1,"26-12-28 ~ 27-01-02"],[2,"27-01-04 ~ 27-01-09"]]""","2026-12-28").map { it.start })
        assertEquals(listOf("2099-12-28","2100-01-04"),parse(
            """[[1,"99-12-28 ~ 00-01-02"],[2,"00-01-04 ~ 00-01-09"]]""","2099-12-28").map { it.start })
    }

    @Test fun `rejects malformed ambiguous and inconsistent catalogs`() {
        for (rows in listOf(
            "[]","null","{}",
            """[[0,"26-11-09 ~ 26-11-14"]]""",
            """[[1,"26-11-09 ~ 26-11-14"],[1,"26-11-16 ~ 26-11-21"]]""",
            """[[1,"26-11-09 ~ 26-11-14"],[2,"26-11-09 ~ 26-11-14"]]""",
            """[[1,"26-11-10 ~ 26-11-14"]]""",
            """[[1,"26-11-09 ~ 26-11-22"]]""",
            """[[1,"26-11-09 ~ 26-11-08"]]""",
            """[[1,"26-11-16 ~ 26-11-21"]]""",
            """[[1,"26-13-09 ~ 26-13-14"]]""",
            """[[1,"tomorrow"]]""",
        )) assertFailsWith<StructureChanged>(rows) { parse(rows) }
        assertFailsWith<StructureChanged> { parse("""[[1,"26-11-09 ~ 26-11-14"]]""",today=9) }
    }

    @Test fun `older responses may omit catalog metadata`() {
        assertTrue(ComciganPublishedWeeks.parse(buildJsonObject {},LocalDate.parse("2026-11-09")).isEmpty())
    }
}

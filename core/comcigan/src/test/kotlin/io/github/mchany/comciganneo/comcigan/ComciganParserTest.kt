package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ComciganParserTest {
    private fun page(wrapped: Boolean = true) = """
        url:'./1234567?9876l'
        sc_data('98765_',sc,1,'0');
        성명=${if (wrapped) "Q성명(" else ""}자료.자료11[th]);
        과목명=Q과목명(자료.자료12[sb]);
        da1=H시간표.자료13;
        일일자료=Q자료(자료.자료14[g][c][d][p]);
        원자료=Q자료(자료.자료15[g][c][d][p]);
    """.trimIndent()
    private val selection = ClassSelection(School(12045, "테스트고", "테스트"), 1, 1)
    private fun fixture(marked: Boolean = true, divisor: Int = 1000): String = buildJsonObject {
        put("시작일", "2026-10-26")
        put("학급수", buildJsonArray { add(1); add(1) })
        put("분리", divisor)
        put("변경알림", if (marked) 1 else 0)
        put("자료11", buildJsonArray { repeat(124) { add("교사$it*") } })
        put("자료12", buildJsonArray { repeat(9) { add("과목$it") } })
        put("자료13", "2026-10-27 08:00:00")
        put("일과시간", buildJsonArray { add("1(08:30)"); add("2(09:30)"); add("3(10:30)") })
        // Arrays are [grade][class][weekday][period], metadata occupies index 0.
        put("자료14", Json.parseToJsonElement(if (marked) "[[],[[],[1,[3,7123,0,\">1007123\"]]]]" else "[[],[[],[1,[3,7123,0,7123]]]]"))
        put("자료15", Json.parseToJsonElement("[[],[[],[1,[3,7123,0,8122]]]]"))
    }.toString()

    @Test fun `extracts both old and wrapped fields without fixed route length`() {
        for (wrapped in listOf(true, false)) {
            val codes = ComciganParser.serviceCodes(page(wrapped))
            assertEquals("/1234567?9876l", codes.searchRoute)
            assertEquals("자료14", codes.currentField)
            assertEquals("자료15", codes.originalField)
            assertEquals("/1234567?OTg3NjVfMTIwNDVfMF8y", codes.timetablePath(12045, 1))
        }
    }
    @Test fun `uses Korean search encoding`() {
        assertTrue(ComciganParser.serviceCodes(page()).searchPath("학교").endsWith("%C7%D0%B1%B3"))
    }
    @Test fun `detects changed service page`() {
        assertFailsWith<StructureChanged> { ComciganParser.serviceCodes("<html>maintenance</html>") }
    }
    @Test fun `parses search padding and preserves separate school codes`() {
        val schools = ComciganParser.schools("{\"학교검색\":[[1,\"서울\",\"가고\",10],[1,\"경기\",\"가고\",20],[1,\"경기\",\"없음\",0]]}\u0000\r\n")
        assertEquals(listOf(10, 20), schools.map { it.code })
    }
    @Test fun `keeps blank periods three digit teachers and changed current direction`() {
        val snapshot = ComciganParser.timetable(fixture(), ComciganParser.serviceCodes(page()), selection, 123)
        assertEquals(listOf(1, 2, 3), snapshot.slots.map { it.period })
        assertNull(snapshot.slots[1].current)
        val changed = snapshot.slots[2]
        assertEquals("과목7", changed.current?.subject)
        assertEquals("교사123*", changed.current?.teacher)
        assertEquals("과목8", changed.original?.subject)
        assertTrue(changed.changed)
        assertEquals("10:30", changed.startTime)
        assertEquals("2026-10-26", changed.date)
        assertEquals(3, snapshot.slots.size) // No artificial eight-period padding.
    }
    @Test fun `supports legacy changes without marker`() {
        val snapshot = ComciganParser.timetable(fixture(marked = false, divisor = 100), ComciganParser.serviceCodes(page()), selection, 123)
        assertTrue(snapshot.slots.last().changed)
        assertFalse(snapshot.slots.first().changed)
    }
    @Test fun `ignores storage padding after declared final period`() {
        val padded = fixture().replace("1007123\"]", "1007123\",0,0,0,0,0]")
        val snapshot = ComciganParser.timetable(padded, ComciganParser.serviceCodes(page()), selection, 123)
        assertEquals(3, snapshot.slots.size)
    }
    @Test fun `empty next week is unavailable`() {
        assertFailsWith<TimetableUnavailable> { ComciganParser.timetable("{}", ComciganParser.serviceCodes(page()), selection, 0) }
    }
    @Test fun `rejects invalid class and malformed codes`() {
        assertFailsWith<StructureChanged> { ComciganParser.timetable(fixture(), ComciganParser.serviceCodes(page()), selection.copy(classNumber = 2), 0) }
        assertFailsWith<StructureChanged> { ComciganParser.timetable(fixture().replace("1007123", "bad"), ComciganParser.serviceCodes(page()), selection, 0) }
    }
}

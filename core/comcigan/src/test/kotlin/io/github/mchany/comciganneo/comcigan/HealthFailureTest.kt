package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlin.test.*

class HealthFailureTest {
    private val codes = ServiceCodes("/123", "search", "token", "teachers", "subjects", "updated", "current", "original")
    private val selection = ClassSelection(School(1, "school", "region"), 1, 1)

    @Test fun responseFailuresHaveParseSourceWhilePageFailuresKeepProtocolSource() {
        for (body in listOf("not-json", "[]", """{"학교검색":"broken"}""")) {
            val error = assertFailsWith<StructureChanged> { ComciganParser.schools(body) }
            assertEquals("PARSE", error.source.toString())
        }
        val page = assertFailsWith<StructureChanged> { ComciganParser.serviceCodes("<html>maintenance</html>") }
        assertEquals("PROTOCOL", page.source.toString())
    }

    @Test fun malformedEncryptionHasParseSource() {
        val error = assertFailsWith<StructureChanged> { ComciganResponseDecoder.decode("enc5:bad", "00".repeat(32)) }
        assertEquals("PARSE", error.source.toString())
    }

    @Test fun missingStartDoesNotProveUnpublished() {
        for (body in listOf("{}", """{"other":"field"}""")) {
            val error = assertFailsWith<TimetableUnavailable> { ComciganParser.timetable(body, codes, selection, 0) }
            assertEquals("AMBIGUOUS", error.evidence.toString())
        }
    }

    @Test fun missingDataFieldsAreParseFailures() {
        val error = assertFailsWith<StructureChanged> {
            ComciganParser.timetable("""{"시작일":"2026-11-16"}""", codes, selection, 0)
        }
        assertEquals("PARSE", error.source.toString())
    }
}

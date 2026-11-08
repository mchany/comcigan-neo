package io.github.mchany.comciganneo.neis

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.time.LocalDate
import kotlin.test.*

class NeisClientTest {
    private val school = NeisSchoolRef("G10", "7430065", "테스트학교", "교육청", "주소")
    private val date = LocalDate.of(2026, 10, 27)
    /**
     * 역할: NEIS 응답 행을 급식 정보로 변환한다.
     * 입력: code: String, ymd: String.
     * 출력: JsonObject 값.
     */
    private fun meal(code: String = "2", ymd: String = "20261027") = buildJsonObject {
        put("ATPT_OFCDC_SC_CODE", "G10"); put("SD_SCHUL_CODE", "7430065")
        put("MMEAL_SC_CODE", code); put("MLSV_YMD", ymd)
        put("DDISH_NM", "잡곡밥 100g<BR />돼지고기 (5.6.10)<br>과일 &amp; 우유")
        put("CAL_INFO", JsonNull); put("NTR_INFO", "단백질:20g<br/>철분:3mg")
        put("new_field", "ignored")
    }
    /**
     * 역할: 응답 본문을 API 페이지 모델로 분석한다.
     * 입력: total: Int, rows: List<JsonObject>, endpoint: String.
     * 출력: JsonObject 값.
     */
    private fun page(total: Int, rows: List<JsonObject>, endpoint: String = "mealServiceDietInfo") = buildJsonObject {
        putJsonArray(endpoint) {
            addJsonObject { putJsonArray("head") {
                addJsonObject { put("list_total_count", total) }
                addJsonObject { putJsonObject("RESULT") { put("CODE", "INFO-000") } }
            } }
            addJsonObject { put("row", JsonArray(rows)) }
        }
    }.toString()

    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun keepsMenuNumbersAndOptionalFields() {
        val parsed = NeisParser.meal(meal())
        assertEquals(listOf("잡곡밥 100g", "돼지고기 (5.6.10)", "과일 & 우유"), parsed.menu)
        assertTrue(parsed.rawMenu.contains("<BR />"))
        assertNull(parsed.calories)
        assertEquals("단백질:20g\n철분:3mg", parsed.nutrition)
        assertEquals("", parsed.origin)
        assertEquals(MealType.LUNCH, parsed.type)
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun preservesUnknownMealType() {
        val parsed = NeisParser.meal(meal("9"))
        assertEquals(MealType.UNKNOWN, parsed.type)
        assertEquals("9", parsed.typeCode)
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun distinguishesEmptyFromApiFailure() {
        assertEquals(0, NeisParser.page("""{"RESULT":{"CODE":"INFO-200"}}""", "mealServiceDietInfo").total)
        assertEquals("ERROR-290", assertFailsWith<NeisApiException> {
            NeisParser.page("""{"RESULT":{"CODE":"ERROR-290"}}""", "mealServiceDietInfo")
        }.code)
        assertFailsWith<NeisStructureChanged> { NeisParser.page("{}", "mealServiceDietInfo") }
        assertFailsWith<NeisStructureChanged> { NeisParser.page("<html>maintenance</html>", "mealServiceDietInfo") }
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun rejectsHeadErrorAndMalformedMenu() {
        assertFailsWith<NeisApiException> {
            NeisParser.page(page(1, listOf(meal())).replace("INFO-000", "ERROR-300"), "mealServiceDietInfo")
        }
        assertFailsWith<NeisStructureChanged> { NeisParser.meal(meal(ymd = "20260230")) }
        assertFailsWith<NeisStructureChanged> { NeisParser.meal(JsonObject(meal().filterKeys { it != "DDISH_NM" })) }
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun keyRequiredBeforeNetwork() = runBlocking<Unit> {
        val client = NeisClient("", NeisTransport { error("Must not call network") })
        assertFailsWith<NeisKeyRequired> { client.searchSchools("테스트") }
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun collectsEveryPageAndUsesStringCodes() = runBlocking {
        val requested = mutableListOf<String>()
        val client = NeisClient("test-key", NeisTransport { url ->
            requested += url.queryParameter("pIndex")!!
            assertEquals("test-key", url.queryParameter("KEY"))
            assertEquals("G10", url.queryParameter("ATPT_OFCDC_SC_CODE"))
            assertEquals("7430065", url.queryParameter("SD_SCHUL_CODE"))
            if (requested.size == 1) page(2, listOf(meal("2"))) else page(2, listOf(meal("3")))
        })
        assertEquals(2, client.meals(school, date, date).size)
        assertEquals(listOf("1", "2"), requested)
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun neverAcceptsTruncatedSampleOrChangedTotal() = runBlocking<Unit> {
        val sample = NeisClient("", NeisTransport { page(9, listOf(meal())) }, allowSample = true)
        assertFailsWith<NeisIncomplete> { sample.meals(school, date, date) }
        var index = 0
        val changed = NeisClient("test", NeisTransport { page(if (++index == 1) 2 else 3, listOf(meal())) })
        assertFailsWith<NeisIncomplete> { changed.meals(school, date, date) }
        val emptySecond = NeisClient("test", NeisTransport { url ->
            if (url.queryParameter("pIndex") == "1") page(2, listOf(meal())) else page(2, emptyList())
        })
        assertFailsWith<NeisIncomplete> { emptySecond.meals(school, date, date) }
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun rejectsWrongSchoolWrongDateAndDuplicateMeals() = runBlocking {
        for (rows in listOf(listOf(JsonObject(meal().toMutableMap().apply { put("SD_SCHUL_CODE", JsonPrimitive("OTHER")) })),
            listOf(meal(ymd = "20261028")), listOf(meal(), meal()))) {
            val client = NeisClient("test", NeisTransport { page(rows.size, rows) })
            assertFailsWith<NeisStructureChanged> { client.meals(school, date, date) }
        }
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun schoolQueryIsEncodedAndAllCandidatesReturned() = runBlocking {
        val schools = listOf("001", "002").map { code -> buildJsonObject {
            put("ATPT_OFCDC_SC_CODE", "B10"); put("SD_SCHUL_CODE", code); put("SCHUL_NM", "같은학교")
            put("ORG_RDNMA", "주소 $code")
        } }
        val client = NeisClient("test", NeisTransport { url ->
            assertEquals("학교 & 고등", url.queryParameter("SCHUL_NM"))
            page(2, schools, "schoolInfo")
        })
        assertEquals(listOf("001", "002"), client.searchSchools("학교 & 고등").map { it.schoolCode })
    }
    /**
     * 역할: 테스트 시나리오의 기대 결과를 검증한다.
     * 입력: 없음.
     * 출력: 없음(Unit).
     */
    @Test fun cancellationIsNotConvertedToEmptyOrApiError() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val client = NeisClient("test", NeisTransport { entered.complete(Unit); awaitCancellation() })
        val request = async { client.meals(school, date, date) }
        entered.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }
}

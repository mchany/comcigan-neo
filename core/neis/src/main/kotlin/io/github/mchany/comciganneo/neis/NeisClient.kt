// API design and field mapping based on kimcore/neis.kt (MIT). See LICENSES/neis.kt-MIT.txt.
package io.github.mchany.comciganneo.neis

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 역할: 지정한 경로의 네트워크 응답을 조회한다.
 * 입력: url: HttpUrl.
 * 출력: String } 값.
 */
fun interface NeisTransport { suspend fun get(url: HttpUrl): String }

class OkHttpNeisTransport : NeisTransport {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    /**
     * 역할: 지정한 경로의 네트워크 응답을 조회한다.
     * 입력: url: HttpUrl.
     * 출력: String 값.
     */
    override suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            /**
             * 역할: 네트워크 요청 실패를 전달한다.
             * 입력: call: Call, e: IOException.
             * 출력: 없음(Unit).
             */
            override fun onFailure(call: Call, e: IOException) {
                // Never surface request URLs: they contain the API key.
                if (!continuation.isCancelled) continuation.resumeWithException(IOException("NEIS connection failed"))
            }
            /**
             * 역할: 네트워크 응답을 읽고 결과를 전달한다.
             * 입력: call: Call, response: Response.
             * 출력: 없음(Unit).
             */
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("NEIS HTTP ${it.code}")
                        val source = it.body?.source() ?: throw IOException("Empty NEIS response")
                        if (source.request(4L * 1024 * 1024 + 1)) throw IOException("NEIS response too large")
                        source.readUtf8()
                    }
                    continuation.resume(body) { _, _, _ -> }
                } catch (_: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(IOException("NEIS response failed"))
                }
            }
        })
    }
}

class NeisClient(
    private val apiKey: String,
    private val transport: NeisTransport = OkHttpNeisTransport(),
    private val allowSample: Boolean = false,
) {
    /**
     * 역할: NEIS API에서 조건에 맞는 모든 행을 조회한다.
     * 입력: endpoint: String, params: Map<String, String>.
     * 출력: List<JsonObject> 값.
     */
    private suspend fun rows(endpoint: String, params: Map<String, String>): List<JsonObject> {
        if (apiKey.isBlank() && !allowSample) throw NeisKeyRequired()
        val result = mutableListOf<JsonObject>()
        var expected: Int? = null
        for (index in 1..100) {
            currentCoroutineContext().ensureActive()
            val url = "https://open.neis.go.kr/hub/$endpoint".toHttpUrl().newBuilder()
                .addQueryParameter("Type", "json").addQueryParameter("pSize", "100").addQueryParameter("pIndex", index.toString())
                .apply {
                    if (apiKey.isNotBlank()) addQueryParameter("KEY", apiKey.trim())
                    params.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
            val page = NeisParser.page(transport.get(url), endpoint)
            if (expected != null && expected != page.total) throw NeisIncomplete()
            expected = page.total
            if (page.total == 0) return emptyList()
            if (page.rows.isEmpty()) throw NeisIncomplete()
            result += page.rows
            if (result.size == page.total) return result
            if (result.size > page.total || apiKey.isBlank()) throw NeisIncomplete()
        }
        throw NeisIncomplete()
    }

    /**
     * 역할: 검색어에 맞는 학교 목록을 조회한다.
     * 입력: query: String.
     * 출력: List<NeisSchoolRef> 값.
     */
    suspend fun searchSchools(query: String): List<NeisSchoolRef> {
        require(query.trim().length >= 2)
        val schools = rows("schoolInfo", mapOf("SCHUL_NM" to query.trim())).map(NeisParser::school)
        if (schools.distinctBy { it.officeCode to it.schoolCode }.size != schools.size) throw NeisIncomplete()
        return schools
    }

    /**
     * 역할: 학교와 날짜 범위에 해당하는 급식 목록을 조회한다.
     * 입력: school: NeisSchoolRef, from: LocalDate, to: LocalDate.
     * 출력: List<MealEntry> 값.
     */
    suspend fun meals(school: NeisSchoolRef, from: LocalDate, to: LocalDate): List<MealEntry> {
        require(!to.isBefore(from) && to <= from.plusDays(31))
        val rows = rows("mealServiceDietInfo", mapOf("ATPT_OFCDC_SC_CODE" to school.officeCode,
            "SD_SCHUL_CODE" to school.schoolCode, "MLSV_FROM_YMD" to from.format(DateTimeFormatter.BASIC_ISO_DATE),
            "MLSV_TO_YMD" to to.format(DateTimeFormatter.BASIC_ISO_DATE)))
        val meals = rows.map { row ->
            if (row["ATPT_OFCDC_SC_CODE"]?.jsonPrimitive?.content != school.officeCode ||
                row["SD_SCHUL_CODE"]?.jsonPrimitive?.content != school.schoolCode) throw NeisStructureChanged()
            NeisParser.meal(row).also {
                if (LocalDate.parse(it.date) !in from..to) throw NeisStructureChanged()
            }
        }
        if (meals.distinctBy { it.date to it.typeCode }.size != meals.size) throw NeisStructureChanged()
        return meals.sortedWith(compareBy({ it.date }, { it.typeCode }))
    }
}

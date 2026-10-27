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

fun interface NeisTransport { suspend fun get(url: HttpUrl): String }

class OkHttpNeisTransport : NeisTransport {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
    override suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Never surface request URLs: they contain the API key.
                if (!continuation.isCancelled) continuation.resumeWithException(IOException("NEIS connection failed"))
            }
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

    suspend fun searchSchools(query: String): List<NeisSchoolRef> {
        require(query.trim().length >= 2)
        val schools = rows("schoolInfo", mapOf("SCHUL_NM" to query.trim())).map(NeisParser::school)
        if (schools.distinctBy { it.officeCode to it.schoolCode }.size != schools.size) throw NeisIncomplete()
        return schools
    }

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

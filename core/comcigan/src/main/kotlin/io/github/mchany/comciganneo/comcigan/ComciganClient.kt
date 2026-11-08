package io.github.mchany.comciganneo.comcigan

import io.github.mchany.comciganneo.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ComciganRequestRejected : IOException("자료 조회 요청이 거부됐어요. (403)")
class ComciganRateLimited : IOException("조회 요청이 너무 많아요. 잠시 후 다시 시도해 주세요. (429)")

class ComciganClient internal constructor(
    private val baseUrl: String, private val http: OkHttpClient, private val today: () -> LocalDate = ::schoolToday,
) {
    constructor() : this("http://comci.net:4082", OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).build())
    private val lock = Mutex()
    private var cachedCodes: Pair<Long, ServiceCodes>? = null
    /**
     * 역할: 지정한 경로의 네트워크 응답을 조회한다.
     * 입력: path: String, charset: Charset, 선택적 요청 토큰 token: HourRequestToken?.
     * 출력: String 값.
     */
    private fun get(path: String, charset: Charset, token: HourRequestToken? = null): String {
        val request = Request.Builder().url("$baseUrl$path")
            .header("User-Agent", "Mozilla/5.0 ComciganNeo/0.1 (Android)")
            .apply { token?.let { header("X-Hour-Token", it.headerValue()) } }.build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 403) throw ComciganRequestRejected()
            if (response.code == 429) throw ComciganRateLimited()
            if (!response.isSuccessful) throw IOException("서버 연결에 실패했어요. (${response.code})")
            val bytes = response.body?.bytes() ?: throw IOException("서버 응답이 비어 있어요.")
            if (bytes.size > 16 * 1024 * 1024) throw IOException("서버 응답이 너무 커요.")
            bytes.toString(charset)
        }
    }
    /**
     * 역할: 서버 서비스 코드를 조회하거나 캐시된 코드를 반환한다.
     * 입력: 없음.
     * 출력: ServiceCodes 값.
     */
    private suspend fun codes(): ServiceCodes = lock.withLock {
        cachedCodes?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.second
            ?: ComciganParser.serviceCodes(get("/st", Charset.forName("MS949"))).also {
                cachedCodes = System.currentTimeMillis() to it
            }
    }
    private suspend fun <T> withCodes(block: (ServiceCodes) -> T): T {
        repeat(2) { attempt ->
            val current = codes()
            try { return block(current) }
            catch (e: Exception) {
                if (e !is StructureChanged && e !is ComciganRequestRejected && e !is TimetablePeriodChanged) throw e
                // Re-read a rotated page or rejected/expired credential at most once. Never retry 429.
                lock.withLock { if (cachedCodes?.second == current) cachedCodes = null }
                if (attempt == 1) throw e
            }
        }
        error("Unreachable retry state")
    }
    /**
     * 역할: 검색어에 맞는 학교 목록을 조회한다.
     * 입력: query: String.
     * 출력: List<School> 값.
     */
    suspend fun searchSchools(query: String): List<School> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 2) { "학교 이름을 두 글자 이상 입력해 주세요." }
        withCodes { codes -> ComciganParser.schools(get(codes.searchPath(query.trim()), Charsets.UTF_8, codes.requestToken), codes) }
    }
    /**
     * 역할: 제한 시간 안에 외부 자료를 조회한다.
     * 입력: selection: ClassSelection, week: Int.
     * 출력: TimetableSnapshot 값.
     */
    suspend fun fetch(selection: ClassSelection, week: Int): TimetableSnapshot = withContext(Dispatchers.IO) {
        require(week in 0..1)
        withCodes { codes ->
            val date = today()
            fun read(request: Int) = ComciganParser.timetable(
                get(codes.timetableRequestPath(selection.school.code, request), Charsets.UTF_8, codes.requestToken),
                codes, selection, System.currentTimeMillis())
            // Request 1 is the official school's initial load. Discover its date/selector catalog afresh.
            val initial = read(1)
            val expected = initial.publishedWeekStarts(date).getOrNull(week) ?: throw TimetableUnavailable()
            val request = if (initial.publishedWeeks.isEmpty()) week + 1
                else initial.publishedWeeks.single { it.start == expected }.request
            val result = if (request == 1) initial else read(request)
            // A rotated list or wrong-period response must never be presented as unpublished or cached.
            if (today() != date || result.weekStart != expected || !result.matchesPublishedWeek(week, date)) {
                throw TimetablePeriodChanged()
            }
            result
        }
    }
}

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
import java.util.concurrent.TimeUnit

class ComciganClient {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val lock = Mutex()
    private var cachedCodes: Pair<Long, ServiceCodes>? = null
    /**
     * 역할: 지정한 경로의 네트워크 응답을 조회한다.
     * 입력: path: String, charset: Charset.
     * 출력: String 값.
     */
    private fun get(path: String, charset: Charset): String {
        val request = Request.Builder().url("http://comci.net:4082$path")
            .header("User-Agent", "Mozilla/5.0 ComciganNeo/0.1 (Android)").build()
        return http.newCall(request).execute().use { response ->
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
    /**
     * 역할: 검색어에 맞는 학교 목록을 조회한다.
     * 입력: query: String.
     * 출력: List<School> 값.
     */
    suspend fun searchSchools(query: String): List<School> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 2) { "학교 이름을 두 글자 이상 입력해 주세요." }
        val codes = codes()
        ComciganParser.schools(get(codes.searchPath(query.trim()), Charsets.UTF_8))
    }
    /**
     * 역할: 제한 시간 안에 외부 자료를 조회한다.
     * 입력: selection: ClassSelection, week: Int.
     * 출력: TimetableSnapshot 값.
     */
    suspend fun fetch(selection: ClassSelection, week: Int): TimetableSnapshot = withContext(Dispatchers.IO) {
        val codes = codes()
        try {
            val snapshot = ComciganParser.timetable(get(codes.timetablePath(selection.school.code, week), Charsets.UTF_8),
                codes, selection, System.currentTimeMillis())
            // Never display this week's data under a next-week heading.
            if (snapshot.weekStart != weekStart(offset = week).toString()) throw TimetableUnavailable()
            snapshot
        } catch (e: StructureChanged) {
            lock.withLock { cachedCodes = null }
            throw e
        }
    }
}

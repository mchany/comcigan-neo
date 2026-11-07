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

class ComciganRequestRejected : IOException("자료 조회 요청이 거부됐어요. (403)")
class ComciganRateLimited : IOException("조회 요청이 너무 많아요. 잠시 후 다시 시도해 주세요. (429)")

class ComciganClient internal constructor(private val baseUrl: String, private val http: OkHttpClient) {
    constructor() : this("http://comci.net:4082", OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).build())
    private val lock = Mutex()
    private var cachedCodes: Pair<Long, ServiceCodes>? = null
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
                if (e !is StructureChanged && e !is ComciganRequestRejected) throw e
                // Re-read a rotated page or rejected/expired credential at most once. Never retry 429.
                lock.withLock { if (cachedCodes?.second == current) cachedCodes = null }
                if (attempt == 1) throw e
            }
        }
        error("Unreachable retry state")
    }
    suspend fun searchSchools(query: String): List<School> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 2) { "학교 이름을 두 글자 이상 입력해 주세요." }
        withCodes { codes -> ComciganParser.schools(get(codes.searchPath(query.trim()), Charsets.UTF_8, codes.requestToken), codes) }
    }
    suspend fun fetch(selection: ClassSelection, week: Int): TimetableSnapshot = withContext(Dispatchers.IO) {
        withCodes { codes ->
            val snapshot = ComciganParser.timetable(get(codes.timetablePath(selection.school.code, week), Charsets.UTF_8, codes.requestToken),
                codes, selection, System.currentTimeMillis())
            // Never display this week's data under a next-week heading.
            if (snapshot.weekStart != weekStart(offset = week).toString()) throw TimetableUnavailable()
            snapshot
        }
    }
}

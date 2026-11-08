package io.github.mchany.comciganneo.data

import androidx.room.withTransaction
import io.github.mchany.comciganneo.model.SchoolZone
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 역할: 캐시에 기록된 조회·저장 시각부터 한국 시간 기준 3개월이 지났는지 확인한다.
 * 입력: 저장된 스냅샷 JSON과 현재 시각의 epoch 밀리초 값.
 * 출력: 만료됐으면 true이며 저장 시각을 읽을 수 없으면 false.
 */
internal fun isCacheExpired(snapshotJson: String, now: Long): Boolean {
    val fetchedAt = runCatching {
        Json.parseToJsonElement(snapshotJson).jsonObject["fetchedAt"]?.jsonPrimitive?.longOrNull
    }.getOrNull() ?: return false
    val expiresAt = Instant.ofEpochMilli(fetchedAt).atZone(SchoolZone).plusMonths(3).toInstant()
    return !expiresAt.isAfter(Instant.ofEpochMilli(now))
}

/**
 * 역할: 모든 학교의 만료된 시간표와 급식 캐시를 하나의 트랜잭션에서 삭제한다.
 * 입력: 캐시 데이터베이스와 현재 시각의 epoch 밀리초 값.
 * 출력: 두 캐시 테이블에서 삭제한 행 수의 합.
 */
suspend fun TimetableDatabase.deleteExpiredCache(now: Long = System.currentTimeMillis()): Int = withTransaction {
    val timetables = snapshots().all().filter { isCacheExpired(it.json, now) }
    val mealSnapshots = meals().all().filter { isCacheExpired(it.json, now) }
    snapshots().delete(timetables) + meals().delete(mealSnapshots)
}

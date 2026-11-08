package io.github.mchany.comciganneo.cache

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mchany.comciganneo.NeoApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CacheCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /**
     * 역할: 네트워크 조회 없이 저장 후 3개월이 지난 캐시를 정리한다.
     * 입력: WorkManager가 전달한 작업 실행 정보와 앱 저장소.
     * 출력: 정리 완료 시 success, 저장소 오류 시 retry이며 취소는 그대로 전달한다.
     */
    override suspend fun doWork(): Result {
        return try {
            (applicationContext as NeoApplication).repository.deleteExpiredCache()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        /**
         * 역할: 위젯이나 알림 사용 여부와 관계없이 하루 한 번 캐시를 정리하도록 예약한다.
         * 입력: Android 앱 컨텍스트.
         * 출력: 없음(Unit)이며 기존 예약이 있으면 유지해 중복 등록을 막는다.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "neo-cache-cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}

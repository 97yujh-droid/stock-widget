package com.yujh.todaymarket.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yujh.todaymarket.data.Repository
import java.util.concurrent.TimeUnit

/**
 * 30분마다 새 결과 파일을 받아서 위젯을 다시 그린다.
 * 인터넷이 연결돼 있을 때만 실행해서 배터리와 데이터를 아낀다.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = runCatching { Repository.fetch(applicationContext) }.isSuccess
        // 받기에 실패해도 위젯은 다시 그린다 ('지난 데이터' 표시를 위해)
        runCatching { TodayWidget().updateAll(applicationContext) }
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "today-market-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

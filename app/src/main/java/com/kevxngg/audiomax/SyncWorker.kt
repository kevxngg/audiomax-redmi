package com.kevxngg.audiomax

import android.content.Context
import android.media.AudioManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Live changes don't reach the Dolby service's memory: when the output changes it would
 * restore the old values. This job restarts Dolby (so it reads the already saved database)
 * only when nothing is playing, so nobody hears the cut.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val am = applicationContext.getSystemService(AudioManager::class.java)
        if (am != null && (am.isMusicActive || am.mode != AudioManager.MODE_NORMAL)) return Result.retry()
        if (!Root.available()) return Result.retry()
        DolbyStore(applicationContext).restartAudio()
        return Result.success()
    }

    companion object {
        /** soon = there are settings that only apply this way (width, protection…): try right away. */
        fun schedule(ctx: Context, soon: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(if (soon) 5L else 120L, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dolby-sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

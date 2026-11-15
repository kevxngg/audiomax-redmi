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
 * Los cambios en vivo no llegan a la memoria del servicio de Dolby: al cambiar de salida
 * volvería a poner los valores antiguos. Este trabajo reinicia Dolby (para que lea la base
 * de datos ya guardada) solo cuando no está sonando nada, así nadie oye el corte.
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
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dolby-sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

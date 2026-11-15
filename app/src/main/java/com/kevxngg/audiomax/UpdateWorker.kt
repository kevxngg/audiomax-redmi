package com.kevxngg.audiomax

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Checks GitHub every hour and posts a notification when there is a new version. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result =
        if (runCheck(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val CHANNEL = "app_updates"
        private const val NOTIF_ID = 1001
        private const val WORK = "update-check"

        @Synchronized
        fun runCheck(ctx: Context): Boolean {
            val info = Updater.fetch(ctx) ?: return false
            if (!Updater.isOffered(ctx, info)) return true
            val sp = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
            if (sp.getInt("notified", 0) >= info.build) return true
            if (notify(ctx, info)) sp.edit().putInt("notified", info.build).apply()
            return true
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** With root: notification permission and no battery restrictions for the checks. */
        fun prepare(ctx: Context) {
            val p = ctx.packageName
            val cmds = mutableListOf(
                "cmd deviceidle whitelist +$p",
                "appops set $p RUN_IN_BACKGROUND allow",
                "appops set $p RUN_ANY_IN_BACKGROUND allow",
                // HyperOS/MIUI: autostart (otherwise it blocks checks, notifications and the Quick Settings tile)
                "appops set $p 10008 allow",
            )
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) cmds.add("pm grant $p android.permission.POST_NOTIFICATIONS")
            Root.run(*cmds.toTypedArray())
        }

        private fun channel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.updates), NotificationManager.IMPORTANCE_HIGH)
            )
        }

        fun showProgress(ctx: Context) {
            channel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.notif_downloading))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }

        fun showFailed(ctx: Context, url: String) {
            channel(ctx)
            val browser = PendingIntent.getActivity(
                ctx, 2, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.update_available_title))
                .setContentText(ctx.getString(R.string.notif_failed_text))
                .setContentIntent(browser)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        }

        private fun notify(ctx: Context, info: UpdateInfo): Boolean {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            channel(ctx)
            val install = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, InstallUpdateReceiver::class.java).putExtra("apk", info.apkUrl)
                    .putExtra("sha256", info.sha256).putExtra("size", info.sizeBytes),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val firstLines = info.notes.lines().map { it.trim() }.filter { it.startsWith("- ") }
                .take(3).joinToString("\n") { "• " + it.drop(2) }
            val text = ctx.getString(R.string.notif_version_available, info.label)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.notif_new_version_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (firstLines.isNotEmpty()) "\n" + firstLines else ""))
                .setContentIntent(open)
                .addAction(0, ctx.getString(R.string.update_now), install)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            return try {
                NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
                true
            } catch (_: SecurityException) { false }
        }
    }
}

/** The notification's "Update now" button: downloads and installs without opening the app. */
class InstallUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val url = intent.getStringExtra("apk") ?: return
        val pending = goAsync()
        UpdateWorker.showProgress(app)
        Thread {
            try {
                if (!Root.available()) throw IllegalStateException()
                val file = Updater.download(app, url) {}
                if (!Updater.install(app, file, intent.getStringExtra("sha256"), intent.getLongExtra("size", 0L)))
                    throw IllegalStateException()
            } catch (e: Exception) {
                UpdateWorker.showFailed(app, url)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Reschedules the check when the phone boots or after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        UpdateWorker.schedule(ctx.applicationContext)
    }
}

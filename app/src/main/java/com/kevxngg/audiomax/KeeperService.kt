package com.kevxngg.audiomax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Keeps the user's settings applied while the app is closed.
 * Dolby (dms-hal) only remembers what is in its database: live changes are lost when the output
 * changes until it reloads. This service reapplies the saved settings when playback starts or the
 * output changes (speaker ↔ headphones).
 */
class KeeperService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var am: AudioManager? = null
    private var endpoint = ""
    private var playing = false

    private val reapply = Runnable {
        runCatching {
            val s = DolbyStore(this).load()
            val ep = Live.activeEndpoint(this)
            val ok = Live.applyAll(s, s.activeProfile.coerceIn(0, 7), ep)
            Log.i(TAG, "reapplied profile=${s.activeProfile} endpoint=$ep ok=$ok")
        }.onFailure { Log.w(TAG, "reapply: ${it.message}") }
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(reapply)
        handler.postDelayed(reapply, delayMs)
    }

    private val devices = object : AudioDeviceCallback() {
        fun changed() {
            val n = Live.activeEndpoint(this@KeeperService)
            if (n != endpoint) {
                endpoint = n
                schedule(900)
            }
        }
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) = changed()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) = changed()
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val now = !configs.isNullOrEmpty()
            if (now && !playing) schedule(500)
            playing = now
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        thread = HandlerThread("keeper").apply { start() }
        handler = Handler(thread.looper)
        am = getSystemService(AudioManager::class.java)
        endpoint = Live.activeEndpoint(this)
        am?.registerAudioDeviceCallback(devices, handler)
        am?.registerAudioPlaybackCallback(playback, handler)
        schedule(1500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        am?.unregisterAudioDeviceCallback(devices)
        am?.unregisterAudioPlaybackCallback(playback)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.keeper_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.keeper_title))
            .setContentText(getString(R.string.keeper_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    companion object {
        private const val TAG = "AudioMaxKeeper"
        private const val CHANNEL = "keeper"
        private const val ID = 7
        private const val PREF = "keeper_on"

        fun enabled(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(PREF, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, KeeperService::class.java))
        }

        fun start(ctx: Context) {
            if (!enabled(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, KeeperService::class.java)) }
                .onFailure { Log.w(TAG, "start: ${it.message}") }
        }
    }
}

package com.kevxngg.audiomax

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Banco de pruebas del sonido (solo en la compilación "measure", no se publica).
 * Reproduce una señal y captura lo que sale DESPUÉS de Dolby con un Visualizer en la sesión 0
 * (el Visualizer se inserta al final de la cadena de efectos).
 *
 *   am broadcast -f 0x10000020 -n com.dolbyatmos.obsidian/com.kevxngg.audiomax.MeasureReceiver
 *     --es set "110:0,0,...;111:1"   parámetros del DAP a escribir antes (perfil activo)
 *     --es sig pink.f32               señal (float32 estéreo 48 kHz) en getExternalFilesDir
 *     --ef secs 4 --ei warm 1500 --es out r.bin
 * Salida: tramas [nanos:int64][1024 bytes u8 mono] y "MEASURE DONE" en logcat (etiqueta AudioMaxMeasure).
 */
class MeasureReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                run(ctx, intent)
            } catch (e: Throwable) {
                log("FALLO ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                log("MEASURE DONE")
                pending.finish()
            }
        }.start()
    }

    private fun log(m: String) = Log.i("AudioMaxMeasure", m)

    private fun run(ctx: Context, intent: Intent) {
        intent.getStringExtra("set")?.takeIf { it.isNotBlank() }?.let { spec ->
            val fx = DolbyEffect.open(100)
            try {
                val prof = intent.getIntExtra("prof", fx.profile)
                for (part in spec.split(";")) {
                    val (id, vals) = part.split(":")
                    val v = vals.split(",").map { it.trim().toInt() }.toIntArray()
                    if (id == "on") fx.dsOn = v[0] == 1
                    else if (id == "profile") fx.profile = v[0]
                    else {
                        val r = runCatching { fx.setRaw(id.toInt(), v, prof); fx.getRaw(id.toInt(), prof, v.size).joinToString(",") }
                        log("SET $id -> ${r.getOrElse { "ERR ${it.message}" }}")
                    }
                }
            } finally {
                fx.release()
            }
        }
        if (intent.getBooleanExtra("dump", false)) {
            val fx = DolbyEffect.open(100)
            try {
                val prof = fx.profile
                log("PARAM dsOn=${fx.dsOn} profile=$prof")
                for (p in DolbyEffect.Param.entries) log("PARAM ${p.id} ${p.name}=${runCatching { fx.get(p, prof).joinToString(",") }.getOrElse { "ERR" }}")
            } finally {
                fx.release()
            }
        }
        val sig = intent.getStringExtra("sig") ?: return
        val secs = intent.getFloatExtra("secs", 4f)
        val warm = intent.getIntExtra("warm", 1500).toLong()
        val dir = ctx.getExternalFilesDir(null)!!
        val raw = File(dir, sig).readBytes()
        val samples = FloatArray(raw.size / 4)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(48000 * 8 / 5)
            .build()
        val vis = Visualizer(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val player = Thread {
            var pos = 0
            val chunk = 4800 * 2
            while (!stop.get()) {
                val n = minOf(chunk, samples.size - pos)
                track.write(samples, pos, n, AudioTrack.WRITE_BLOCKING)
                pos = (pos + n) % samples.size
            }
        }
        try {
            vis.enabled = false
            vis.captureSize = Visualizer.getCaptureSizeRange()[1]
            vis.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            track.play()
            player.start()
            vis.enabled = true
            Thread.sleep(warm)
            val wave = ByteArray(vis.captureSize)
            val out = DataOutputStream(File(dir, intent.getStringExtra("out") ?: "r.bin").outputStream().buffered())
            val end = System.nanoTime() + (secs * 1e9).toLong()
            var frames = 0
            out.use {
                while (System.nanoTime() < end) {
                    if (vis.getWaveForm(wave) == Visualizer.SUCCESS) {
                        it.writeLong(System.nanoTime()); it.write(wave); frames++
                    }
                    Thread.sleep(20)
                }
            }
            log("frames=$frames size=${vis.captureSize}")
        } finally {
            stop.set(true)
            runCatching { player.join(1000) }
            runCatching { vis.enabled = false }
            vis.release()
            runCatching { track.stop() }
            track.release()
        }
    }
}

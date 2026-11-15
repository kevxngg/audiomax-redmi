package com.kevxngg.audiomax

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Diagnóstico del control en vivo (solo se puede lanzar con root):
 *   su -c am broadcast -n com.kevxngg.audiomax/.LiveTestReceiver [--ei prio 100] [--ez write true]
 * Escribe el resultado en logcat con la etiqueta AudioMaxTest.
 */
class LiveTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prio = intent.getIntExtra("prio", 100)
        val write = intent.getBooleanExtra("write", false)
        val pending = goAsync()
        Thread {
            val log = { m: String -> Log.i("AudioMaxTest", m) }
            var fx: DolbyEffect? = null
            try {
                fx = DolbyEffect.open(prio)
                log("control=${fx.hasControl} prio=$prio")
                log("dsOn=${runCatching { fx.dsOn }.getOrElse { "ERR ${it.message}" }}")
                val profile = runCatching { fx.profile }.getOrElse { log("profile ERR ${it.message}"); 0 }
                log("profile=$profile")
                for (p in DolbyEffect.Param.entries) {
                    val v = runCatching { fx.get(p, profile).joinToString(",") }.getOrElse { "ERR ${it.message}" }
                    log("${p.name}=$v")
                }
                val from = intent.getIntExtra("from", -1)
                if (from >= 0) {
                    val to = intent.getIntExtra("to", from + 100)
                    for (id in from..to) {
                        val r = runCatching { fx.getRaw(id, profile, 24) }
                        r.onSuccess { v ->
                            val n = v.indexOfLast { it != 0 } + 1
                            log("ID $id = ${v.take(maxOf(n, 1)).joinToString(",")}")
                        }
                    }
                    log("probe fin")
                }
                val setId = intent.getIntExtra("setid", -1)
                if (setId >= 0) {
                    val vals = (intent.getStringExtra("vals") ?: "0").split(",").map { it.trim().toInt() }.toIntArray()
                    val r = runCatching { fx.setRaw(setId, vals, profile); fx.getRaw(setId, profile, vals.size).joinToString(",") }
                    log("SET $setId -> ${r.getOrElse { "ERR ${it.message}" }}")
                }
                if (write) {
                    // Escribe el mismo valor que ya tiene (no cambia el sonido) para ver si se acepta
                    val r = runCatching {
                        val cur = fx.get(DolbyEffect.Param.BASS_ENHANCER_ENABLE, profile)[0]
                        fx.set(DolbyEffect.Param.BASS_ENHANCER_ENABLE, cur, profile)
                        val g = fx.get(DolbyEffect.Param.GEQ_BAND_GAINS, profile)
                        fx.set(DolbyEffect.Param.GEQ_BAND_GAINS, g, profile)
                        "OK"
                    }.getOrElse { "ERR ${it.message}" }
                    log("write=$r")
                }
            } catch (e: Throwable) {
                log("FALLO: ${e.javaClass.simpleName}: ${e.message} / ${e.cause}")
            } finally {
                fx?.release()
                pending.finish()
            }
        }.start()
    }
}

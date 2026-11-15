package com.kevxngg.audiomax

import android.content.Context
import kotlin.math.roundToInt

/**
 * Graves y ganancia extra hechos con el ecualizador (que sí cambia en vivo), como en Lunaris.
 * Se guarda el nivel aplicado por perfil para poder sumarlo o restarlo de la curva.
 */
class ToneStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tone", Context.MODE_PRIVATE)

    fun bass(p: Int) = sp.getInt("bass_$p", 0)
    fun curve(p: Int) = sp.getInt("curve_$p", 0)
    fun gain(p: Int) = sp.getInt("gain_$p", 0)
    fun set(p: Int, bass: Int = bass(p), curve: Int = curve(p), gain: Int = gain(p)) =
        sp.edit().putInt("bass_$p", bass).putInt("curve_$p", curve).putInt("gain_$p", gain).apply()

    companion object {
        val CURVES = listOf(0 to "Profundo", 1 to "Contundente", 2 to "Cálido")

        /** Peso de cada banda (47 Hz … 19.7 kHz) según el tipo de graves. */
        private val WEIGHTS = listOf(
            floatArrayOf(1f, 1f, .95f, .85f, .7f, .5f, .3f, .15f, .05f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(.8f, .95f, 1f, 1f, .85f, .6f, .35f, .15f, .05f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            floatArrayOf(.7f, .8f, .9f, 1f, 1f, .9f, .7f, .45f, .25f, .1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        )

        /** Curva con los graves (dB) aplicados con signo +1 o retirados con -1. */
        fun withBass(g: List<Int>, db: Int, curve: Int, sign: Int): List<Int> {
            val w = WEIGHTS.getOrElse(curve) { WEIGHTS[0] }
            return g.mapIndexed { i, v -> (v + sign * (db * 16 * w.getOrElse(i) { 0f }).roundToInt()).coerceIn(-192, 192) }
        }

        fun withGain(g: List<Int>, db: Int, sign: Int): List<Int> =
            g.map { (it + sign * db * 16).coerceIn(-192, 192) }
    }
}

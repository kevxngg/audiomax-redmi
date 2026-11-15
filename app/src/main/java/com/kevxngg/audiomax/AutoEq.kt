package com.kevxngg.audiomax

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Curvas de corrección de auriculares del proyecto AutoEq (github.com/jaakkopasanen/AutoEq),
 * convertidas a las 20 bandas del ecualizador de Dolby.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private val LINE = Regex("""^- \[(.+?)]\(\./(.+?)\)(?: by (.+))?$""")

    data class Entry(val name: String, val path: String, val source: String)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "DolbyAtmos-Android")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Lista de auriculares (se guarda una semana en caché). */
    fun index(ctx: Context): List<Entry> {
        val f = File(ctx.cacheDir, "autoeq_index.md")
        val text = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000) f.readText()
        else get(BASE + "INDEX.md").also { f.writeText(it) }
        return text.lineSequence().mapNotNull { l ->
            LINE.find(l.trim())?.let { m ->
                Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3].substringBefore(" on ").trim())
            }
        }.toList()
    }

    /** Curva para las frecuencias dadas, en 1/16 dB (±12 dB), centrada para no perder volumen. */
    fun curve(e: Entry, freqs: List<Int>): List<Int> {
        val file = URLEncoder.encode("${e.name} GraphicEQ.txt", "UTF-8").replace("+", "%20")
        val text = get("$BASE${e.path}/$file")
        val pts = text.substringAfter("GraphicEQ:").split(";").mapNotNull { p ->
            val parts = p.trim().split(Regex("\\s+"))
            if (parts.size == 2) parts[0].toDoubleOrNull()?.let { f -> parts[1].toDoubleOrNull()?.let { f to it } } else null
        }.sortedBy { it.first }
        require(pts.size > 2) { "Curva vacía" }
        fun at(f: Double): Double {
            if (f <= pts.first().first) return pts.first().second
            if (f >= pts.last().first) return pts.last().second
            val i = pts.indexOfFirst { it.first >= f }
            val (f0, g0) = pts[i - 1]
            val (f1, g1) = pts[i]
            val t = (ln(f) - ln(f0)) / (ln(f1) - ln(f0))
            return g0 + (g1 - g0) * t
        }
        val db = freqs.map { at(it.toDouble()) }
        // AutoEq baja todo para evitar saturar; se centra en la zona media para no perder volumen
        val mid = db.sorted()[db.size / 2]
        return db.map { (((it - mid) * 16) / 4).roundToInt().times(4).coerceIn(-192, 192) }
    }
}

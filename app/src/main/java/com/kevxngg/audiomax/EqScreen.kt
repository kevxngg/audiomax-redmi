package com.kevxngg.audiomax

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Presets del ecualizador en dB para las 20 bandas de Dolby (47 Hz … 19.7 kHz). */
object EqPresets {
    val list: List<Pair<String, List<Int>>> = listOf(
        "Plano" to List(20) { 0 },
        "Rico" to listOf(4, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 3, 2),
        "Profundo" to listOf(6, 6, 5, 4, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "En V" to listOf(5, 5, 4, 3, 1, 0, -1, -2, -2, -2, -1, 0, 1, 2, 3, 4, 4, 4, 3, 3),
        "Vocal" to listOf(-2, -2, -1, 0, 1, 2, 3, 3, 3, 3, 2, 2, 1, 0, 0, 0, -1, -1, -1, -1),
        "Brillante" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 3, 4, 4, 4, 4, 3, 3),
        "Electrónica" to listOf(6, 5, 4, 2, 0, -1, -1, 0, 0, 1, 1, 2, 2, 3, 3, 3, 2, 2, 1, 1),
        "Rock" to listOf(4, 4, 3, 2, 0, -1, -2, -1, 0, 1, 2, 3, 3, 3, 3, 2, 2, 2, 1, 1),
        "Pop" to listOf(-1, 0, 1, 2, 3, 3, 2, 1, 0, 0, 0, 0, 1, 1, 2, 2, 2, 1, 1, 0),
        "Clásica" to listOf(3, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 3),
        "Podcast" to listOf(-4, -4, -3, -1, 0, 1, 2, 3, 3, 3, 3, 2, 1, 0, -1, -1, -2, -2, -3, -3),
    ).map { (n, db) -> n to db.map { it * 16 } }

    fun nameOf(gains: List<Int>, user: List<Pair<String, List<Int>>> = emptyList()): String =
        (list + user).firstOrNull { it.second == gains }?.first ?: "Personalizado"
}

/** Presets guardados por el usuario. */
class UserPresets(ctx: Context) {
    private val sp = ctx.getSharedPreferences("eq_presets", Context.MODE_PRIVATE)

    fun all(): List<Pair<String, List<Int>>> = try {
        val j = JSONObject(sp.getString("list", "{}") ?: "{}")
        j.keys().asSequence().map { k -> k to j.getString(k).split(",").map { it.trim().toInt() } }
            .sortedBy { it.first.lowercase() }.toList()
    } catch (_: Exception) { emptyList() }

    fun save(name: String, gains: List<Int>) {
        val j = JSONObject(sp.getString("list", "{}") ?: "{}")
        j.put(name, gains.joinToString(","))
        sp.edit().putString("list", j.toString()).apply()
    }

    fun delete(name: String) {
        val j = JSONObject(sp.getString("list", "{}") ?: "{}")
        j.remove(name)
        sp.edit().putString("list", j.toString()).apply()
    }
}

private const val RANGE = 192 // ±12 dB en 1/16 dB

/** Pesos por banda para los controles de tono. */
private val TONE_WEIGHTS = mapOf(
    "Graves" to floatArrayOf(1f, 1f, .95f, .85f, .7f, .5f, .3f, .15f, .05f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Medios" to floatArrayOf(0f, 0f, 0f, .1f, .3f, .55f, .8f, 1f, 1f, 1f, .9f, .7f, .45f, .2f, .05f, 0f, 0f, 0f, 0f, 0f),
    "Agudos" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, .05f, .2f, .4f, .6f, .8f, .9f, 1f, 1f, 1f, 1f),
)

@Composable
fun EqScreen(
    gains: List<Int>, freqs: List<Int>, enabled: Boolean, live: Boolean,
    user: List<Pair<String, List<Int>>>,
    onEnabled: (Boolean) -> Unit, onChange: (List<Int>) -> Unit, onDone: () -> Unit,
    onSavePreset: (String, List<Int>) -> Unit, onDeletePreset: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var selected by remember { mutableIntStateOf(-1) }
    val current = rememberUpdatedState(gains)
    var saveDialog by remember { mutableStateOf(false) }
    var autoEq by remember { mutableStateOf(false) }
    val tone = remember { mutableStateMapOf("Graves" to 0, "Medios" to 0, "Agudos" to 0) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        MainSwitchBar("Usar ecualizador", enabled, onEnabled)
        Spacer(Modifier.height(16.dp))

        val name = EqPresets.nameOf(gains, user)
        Text("Presets", color = cs.primary, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EqPresets.list.forEach { (n, g) ->
                FilterChip(
                    selected = n == name, enabled = enabled,
                    onClick = { onChange(g); onDone() }, label = { Text(n) },
                )
            }
        }
        if (user.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                user.forEach { (n, g) ->
                    InputChip(
                        selected = n == name, enabled = enabled,
                        onClick = { onChange(g); onDone() }, label = { Text(n) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close, "Borrar $n",
                                Modifier.size(18.dp).pointerInput(n) { detectTapGestures { onDeletePreset(n) } }
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val sel = selected
                Text(
                    if (sel in gains.indices) "${hz(freqs.getOrNull(sel) ?: 0)}  ·  ${db(gains[sel])}"
                    else if (live) "Arrastra los puntos: se oye al momento" else "Arrastra los puntos para ajustar",
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = cs.onSurfaceVariant, fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(260.dp)
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            fun set(o: Offset) {
                                val n = current.value.size
                                val b = ((o.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                                selected = b
                                val g = ((((0.5f - o.y / size.height) * 2f * RANGE) / 8f).roundToInt() * 8)
                                    .coerceIn(-RANGE, RANGE)
                                if (current.value[b] != g) onChange(current.value.toMutableList().also { it[b] = g })
                            }
                            detectDragGestures(
                                onDragStart = { set(it) }, onDragEnd = { onDone() }, onDragCancel = { onDone() },
                            ) { change, _ -> set(change.position) }
                        }
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            detectTapGestures { o ->
                                val n = current.value.size
                                val b = ((o.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                                selected = b
                                val g = ((((0.5f - o.y / size.height) * 2f * RANGE) / 8f).roundToInt() * 8)
                                    .coerceIn(-RANGE, RANGE)
                                onChange(current.value.toMutableList().also { it[b] = g })
                                onDone()
                            }
                        }
                ) {
                    val n = gains.size
                    if (n < 2) return@Canvas
                    val w = size.width
                    val h = size.height
                    val alpha = if (enabled) 1f else 0.35f
                    fun px(i: Int) = w * i / (n - 1)
                    fun py(g: Int) = h / 2f - (g.toFloat() / RANGE) * (h / 2f)
                    listOf(-96, 0, 96).forEach { g ->
                        drawLine(
                            cs.outlineVariant.copy(alpha = if (g == 0) 0.9f else 0.4f),
                            Offset(0f, py(g)), Offset(w, py(g)), strokeWidth = if (g == 0) 2f else 1f
                        )
                    }
                    val pts = gains.mapIndexed { i, g -> Offset(px(i), py(g)) }
                    val line = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 0 until n - 1) {
                            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]
                            val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, n - 1)]
                            cubicTo(
                                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y,
                            )
                        }
                    }
                    val fill = Path().apply { addPath(line); lineTo(w, h / 2f); lineTo(0f, h / 2f); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.35f * alpha), cs.primary.copy(alpha = 0.02f))))
                    drawPath(line, cs.primary.copy(alpha = alpha), style = Stroke(width = 6f))
                    pts.forEachIndexed { i, p ->
                        val big = i == selected
                        drawCircle(cs.surface, radius = if (big) 16f else 10f, center = p)
                        drawCircle(cs.primary.copy(alpha = alpha), radius = if (big) 12f else 7f, center = p)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0, 5, 10, 15, 19).forEach { i ->
                        Text(hz(freqs.getOrNull(i) ?: 0), fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = enabled, onClick = { autoEq = true }) { Text("AutoEQ") }
            TextButton(enabled = enabled, onClick = { saveDialog = true }) { Text("Guardar") }
            TextButton(enabled = enabled && gains.any { abs(it) > 0 }, onClick = {
                tone.keys.toList().forEach { tone[it] = 0 }
                onChange(List(gains.size) { 0 }); onDone()
            }) { Text("Restablecer") }
        }

        PrefGroup("Tono rápido") {
            TONE_WEIGHTS.forEach { (label, weights) ->
                SliderPref(
                    label, tone[label] ?: 0, -6, 6, 1, enabled, { String.format(Locale.US, "%+d dB", it) },
                    { v ->
                        val delta = v - (tone[label] ?: 0)
                        if (delta != 0) {
                            tone[label] = v
                            onChange(current.value.mapIndexed { i, g ->
                                (g + ((delta * 16 * weights.getOrElse(i) { 0f }) / 4f).roundToInt() * 4).coerceIn(-RANGE, RANGE)
                            })
                        }
                    },
                    { onDone() },
                )
            }
        }
        FooterText("El tono rápido se suma a la curva. El ecualizador es común a todas las salidas del perfil activo.")
    }

    if (autoEq) AutoEqDialog(freqs, onApply = { g, name ->
        autoEq = false
        onChange(g); onDone()
        onSavePreset("AutoEQ $name".take(24), g)
    }, onDismiss = { autoEq = false })

    if (saveDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Guardar preset") },
            text = {
                OutlinedTextField(text, { text = it.take(24) }, singleLine = true, label = { Text("Nombre") })
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { onSavePreset(text.trim(), current.value); saveDialog = false }) {
                    Text("Guardar")
                }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancelar") } },
        )
    }
}

fun hz(f: Int) = if (f >= 1000) String.format(Locale.US, "%.1f kHz", f / 1000f) else "$f Hz"
fun db(v: Int) = String.format(Locale.US, "%+.1f dB", v / 16f)

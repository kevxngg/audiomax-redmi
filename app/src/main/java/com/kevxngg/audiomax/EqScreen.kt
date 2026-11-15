package com.kevxngg.audiomax

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
    ).map { (n, db) -> n to db.map { it * 16 } }

    fun nameOf(gains: List<Int>): String = list.firstOrNull { it.second == gains }?.first ?: "Personalizado"
}

private const val RANGE = 192 // ±12 dB en 1/16 dB

@Composable
fun EqScreen(
    gains: List<Int>, freqs: List<Int>, enabled: Boolean,
    onEnabled: (Boolean) -> Unit, onChange: (List<Int>) -> Unit, onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var selected by remember { mutableIntStateOf(-1) }
    val current = rememberUpdatedState(gains)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        MainSwitchBar("Usar ecualizador", enabled, onEnabled)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = EqPresets.nameOf(gains)
            (EqPresets.list.map { it.first } + "Personalizado").forEach { n ->
                FilterChip(
                    selected = n == name, enabled = enabled || n == name,
                    onClick = {
                        EqPresets.list.firstOrNull { it.first == n }?.let { onChange(it.second); onDone() }
                    },
                    label = { Text(n) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                val sel = selected
                Text(
                    if (sel in gains.indices) "${hz(freqs.getOrNull(sel) ?: 0)}  ·  ${db(gains[sel])}"
                    else "Arrastra los puntos para ajustar",
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    color = cs.onSurfaceVariant, fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(260.dp)
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            fun band(x: Float) =
                                ((x / size.width) * (current.value.size - 1)).roundToInt()
                                    .coerceIn(0, current.value.size - 1)
                            fun gain(y: Float) =
                                (((0.5f - y / size.height) * 2f * RANGE) / 8f).roundToInt() * 8
                            fun set(o: Offset) {
                                val b = band(o.x)
                                selected = b
                                val g = gain(o.y).coerceIn(-RANGE, RANGE)
                                if (current.value[b] != g) onChange(current.value.toMutableList().also { it[b] = g })
                            }
                            detectDragGestures(
                                onDragStart = { set(it) },
                                onDragEnd = { onDone() },
                                onDragCancel = { onDone() },
                            ) { change, _ -> set(change.position) }
                        }
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            detectTapGestures { o ->
                                val b = ((o.x / size.width) * (current.value.size - 1)).roundToInt()
                                    .coerceIn(0, current.value.size - 1)
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

                    // rejilla: 0 dB y ±6 dB
                    listOf(-96, 0, 96).forEach { g ->
                        drawLine(
                            cs.outlineVariant.copy(alpha = if (g == 0) 0.9f else 0.4f),
                            Offset(0f, py(g)), Offset(w, py(g)), strokeWidth = if (g == 0) 2f else 1f
                        )
                    }

                    // curva suave (Catmull-Rom) y relleno degradado
                    val pts = gains.mapIndexed { i, g -> Offset(px(i), py(g)) }
                    val line = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 0 until n - 1) {
                            val p0 = pts[maxOf(i - 1, 0)]
                            val p1 = pts[i]
                            val p2 = pts[i + 1]
                            val p3 = pts[minOf(i + 2, n - 1)]
                            cubicTo(
                                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                                p2.x, p2.y,
                            )
                        }
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(w, h / 2f); lineTo(0f, h / 2f); close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(
                            listOf(cs.primary.copy(alpha = 0.35f * alpha), cs.primary.copy(alpha = 0.02f))
                        )
                    )
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
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−12 dB … +12 dB", fontSize = 12.sp, color = cs.onSurfaceVariant)
            TextButton(enabled = enabled && gains.any { abs(it) > 0 }, onClick = {
                onChange(List(gains.size) { 0 }); onDone()
            }) { Text("Restablecer") }
        }
        FooterText("El ecualizador es común a todas las salidas del perfil activo.")
    }
}

fun hz(f: Int) = if (f >= 1000) String.format(Locale.US, "%.1f kHz", f / 1000f) else "$f Hz"
fun db(v: Int) = String.format(Locale.US, "%+.1f dB", v / 16f)

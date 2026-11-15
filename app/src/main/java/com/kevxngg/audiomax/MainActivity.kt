package com.kevxngg.audiomax

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = DolbyStore(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3DDC97),
                    secondary = Color(0xFF5AB0FF),
                    background = Color(0xFF0B111B),
                    surface = Color(0xFF121A27),
                    surfaceVariant = Color(0xFF1A2434),
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(store)
                }
            }
        }
    }
}

private fun db(v: Int) = String.format(Locale.US, "%+.1f dB", v / 16f)

@Composable
fun App(store: DolbyStore) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<DolbyStore.State?>(null) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var profile by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf("speaker") }

    fun reload() {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Root.available()) error("Sin root: activa KernelSU/dfroot y da permiso a AudioMax")
                    store.load()
                }
            }
            r.onSuccess { state = it; profile = it.activeProfile.coerceIn(0, 3); errMsg = null }
                .onFailure { errMsg = it.message }
            busy = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun applyAction(action: suspend () -> Root.Result, msg: String) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { action() } }
            val ok = r.getOrNull()?.out?.contains("OK") == true
            busy = false
            reload()
            snack.showSnackbar(if (ok) msg else "Error: ${r.exceptionOrNull()?.message ?: r.getOrNull()?.out}")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            val s = state
            if (s != null) Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { applyAction({ store.restoreOriginal() }, "Restaurado al estado inicial") },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text("Restaurar") }
                Button(
                    onClick = { applyAction({ store.save(s.copy(activeProfile = profile)) }, "Aplicado ✓") },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Aplicando…" else "Aplicar") }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "AudioMax", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Control del Dolby nativo · Redmi Note 14 Pro 4G",
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 13.sp, color = Color.Gray
            )

            errMsg?.let {
                Card { Text(it, Modifier.padding(16.dp), color = Color(0xFFFF7A7A)) }
                Button(onClick = { reload() }, Modifier.fillMaxWidth()) { Text("Reintentar") }
            }
            val s = state ?: run {
                if (errMsg == null) Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            fun setPe(k: String, v: String) {
                val key = "$profile|$endpoint"
                val st = state ?: return
                val cur = st.pe[key] ?: return
                state = st.copy(pe = st.pe + (key to (cur + (k to v))))
            }
            fun setTun(k: String, v: String) {
                val st = state ?: return
                val cur = st.tuning[endpoint] ?: return
                state = st.copy(tuning = st.tuning + (endpoint to (cur + (k to v))))
            }
            val pe = s.pe["$profile|$endpoint"] ?: emptyMap()
            fun b(k: String) = pe[k] == "1"
            fun i(k: String) = pe[k]?.toIntOrNull() ?: 0
            val tun = s.tuning[endpoint] ?: emptyMap()

            Section("Dolby") {
                SwitchRow("Dolby activado", s.dolbyOn) { on -> state = state?.copy(dolbyOn = on) }
                Label("Perfil activo (y el que editas)")
                Chips(DolbyStore.PROFILES, profile) { profile = it }
                Label("Salida que editas")
                Chips(DolbyStore.ENDPOINTS, endpoint) { endpoint = it }
            }

            Section("Bajo") {
                SwitchRow("Realce de bajos", b("bass_enhancer_enable")) {
                    setPe("bass_enhancer_enable", if (it) "1" else "0")
                }
                val maxBoost = if (endpoint == "speaker") 192 else 288
                ValueSlider(
                    "Intensidad (toda la salida)",
                    tun["bass_enhancer_boost"]?.toIntOrNull() ?: 0, 0, maxBoost, 8, ::db
                ) { setTun("bass_enhancer_boost", it.toString()) }
                ValueSlider(
                    "Hasta qué frecuencia",
                    tun["bass_enhancer_cutoff_frequency"]?.toIntOrNull() ?: 160, 60, 300, 10,
                    { "$it Hz" }
                ) { setTun("bass_enhancer_cutoff_frequency", it.toString()) }
                if (endpoint == "speaker") SwitchRow("Bajo virtual (armónicos)", b("virtual_bass_enable")) {
                    setPe("virtual_bass_enable", if (it) "1" else "0")
                }
                if (endpoint == "speaker") Hint("Altavoz limitado a +12 dB para no dañarlo.")
            }

            Section("Ecualizador inteligente") {
                SwitchRow("Activado", b("ieq_enable")) { setPe("ieq_enable", if (it) "1" else "0") }
                Chips(DolbyStore.IEQ_PRESETS, i("selected_ieq_preset_id")) {
                    setPe("selected_ieq_preset_id", it.toString())
                }
                ValueSlider("Cantidad", i("ieq_amount"), 0, 16, 1, { "$it" }) {
                    setPe("ieq_amount", it.toString())
                }
            }

            Section("Ecualizador de 20 bandas") {
                SwitchRow("Activado", b("graphic_equalizer_enable")) {
                    setPe("graphic_equalizer_enable", if (it) "1" else "0")
                }
                Hint("Común a todas las salidas de este perfil.")
                val gains = s.geq[profile]
                if (gains == null) Hint("Este perfil no tiene ecualizador.") else {
                    gains.forEachIndexed { idx, g ->
                        val f = s.geqFreqs.getOrNull(idx) ?: 0
                        val name = if (f >= 1000) String.format(Locale.US, "%.1fk", f / 1000f) else "$f"
                        EqRow(name, g) { v ->
                            val st = state ?: return@EqRow
                            val ng = (st.geq[profile] ?: gains).toMutableList().also { it[idx] = v }
                            state = st.copy(geq = st.geq + (profile to ng))
                        }
                    }
                    TextButton(onClick = {
                        state = s.copy(geq = s.geq + (profile to gains.map { 0 }))
                    }, Modifier.fillMaxWidth()) { Text("Poner plano") }
                }
            }

            Section("Envolvente y diálogos") {
                SwitchRow("Sonido envolvente", b("virtualizer_enable")) {
                    setPe("virtualizer_enable", if (it) "1" else "0")
                }
                ValueSlider("Amplitud envolvente", i("surround_boost"), 0, 96, 4, ::db) {
                    setPe("surround_boost", it.toString())
                }
                SwitchRow("Realce de diálogos", b("dialog_enhancer_enable")) {
                    setPe("dialog_enhancer_enable", if (it) "1" else "0")
                }
                ValueSlider("Intensidad diálogos", i("dialog_enhancer_amount"), 0, 16, 1, { "$it" }) {
                    setPe("dialog_enhancer_amount", it.toString())
                }
            }

            Section("Volumen") {
                ValueSlider("Volumen máximo extra", i("volmax_boost"), 0, 144, 4, ::db) {
                    setPe("volmax_boost", it.toString())
                }
                SwitchRow("Nivelador (todo suena parejo)", b("volume_leveler_enable")) {
                    setPe("volume_leveler_enable", if (it) "1" else "0")
                }
                ValueSlider("Cantidad nivelador", i("volume_leveler_amount"), 0, 10, 1, { "$it" }) {
                    setPe("volume_leveler_amount", it.toString())
                }
            }

            Hint("Al aplicar, el audio se corta un segundo mientras Dolby se reinicia.")
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            content()
        }
    }
}

@Composable
fun Label(t: String) = Text(t, fontSize = 13.sp, color = Color.Gray)

@Composable
fun Hint(t: String) = Text(
    t, fontSize = 12.sp, color = Color.Gray,
    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
)

@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        items.forEach { (id, name) ->
            FilterChip(selected = id == selected, onClick = { onSelect(id) }, label = { Text(name) })
        }
    }
}

@Composable
fun ValueSlider(
    label: String, value: Int, min: Int, max: Int, step: Int,
    fmt: (Int) -> String, onChange: (Int) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text(fmt(value), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
fun EqRow(freq: String, gain: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(freq, Modifier.width(44.dp), fontSize = 12.sp, color = Color.Gray)
        Slider(
            value = gain.coerceIn(-192, 192).toFloat(),
            onValueChange = { onChange((it / 8).roundToInt() * 8) },
            valueRange = -192f..192f,
            modifier = Modifier.weight(1f).height(32.dp)
        )
        Text(
            String.format(Locale.US, "%+.1f", gain / 16f), Modifier.width(44.dp),
            fontSize = 12.sp, textAlign = TextAlign.End
        )
    }
}

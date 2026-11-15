package com.kevxngg.audiomax

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val liveBusy = AtomicBoolean(false)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = DolbyStore(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { App(store) }
            }
        }
    }
}

private enum class Screen { MAIN, EQ }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: DolbyStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<DolbyStore.State?>(null) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    var profile by remember { mutableIntStateOf(0) }
    var endpoint by remember { mutableStateOf(Live.activeEndpoint(ctx)) }
    var activeEp by remember { mutableStateOf(Live.activeEndpoint(ctx)) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var confirmReset by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var advDirty by remember { mutableStateOf(false) }
    val dirty = remember { mutableSetOf<String>() }
    val userPresets = remember { UserPresets(ctx) }
    var presets by remember { mutableStateOf(userPresets.all()) }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Root.available()) error("Sin root: activa KernelSU/dfroot y concede root a la app")
                    val st = store.load()
                    live = Live.available
                    st
                }
            }
            r.onSuccess { state = it; profile = it.activeProfile.coerceIn(0, 3); errMsg = null; advDirty = false }
                .onFailure { errMsg = it.message }
        }
    }

    // Guardado en la base de datos sin reiniciar (para que dure tras reiniciar el teléfono)
    var persistJob by remember { mutableStateOf<Job?>(null) }
    fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1500)
            val s = state ?: return@launch
            withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = profile), restart = false) } }
            SyncWorker.schedule(ctx)
        }
    }

    /** Aplica al momento lo que se puede en vivo. Sin control en vivo, cae al reinicio. */
    fun commit() {
        val s = state ?: return
        val keys = dirty.toSet()
        dirty.clear()
        val p = profile
        val ep = endpoint
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // La salida que no está sonando se guarda y se sincroniza después
                if (ep != Live.activeEndpoint(ctx) && keys.none { it == "dolbyOn" || it == "profile" }) true
                else Live.apply(s.copy(activeProfile = p), p, ep, keys)
            }
            if (ok) persist() else {
                applying = true
                val r = withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = p), restart = true) } }
                applying = false
                if (r.getOrNull()?.out?.contains("OK") != true) snack.showSnackbar("No se pudo aplicar")
            }
        }
    }

    /** Ajustes avanzados: necesitan reiniciar Dolby (corte breve). */
    fun applyAdvanced() {
        val s = state ?: return
        persistJob?.cancel()
        scope.launch {
            applying = true
            val r = withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = profile), restart = true) } }
            applying = false
            if (r.getOrNull()?.out?.contains("OK") == true) advDirty = false
            else snack.showSnackbar("No se pudo aplicar")
        }
    }

    // Sigue a la salida en uso y reaplica los ajustes (Dolby recarga los antiguos al cambiar)
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            fun changed() {
                val n = Live.activeEndpoint(ctx)
                if (n == activeEp) return
                activeEp = n
                endpoint = n
                val s = state ?: return
                val p = profile
                scope.launch(Dispatchers.IO) {
                    delay(800)
                    Live.applyAll(s, p, n)
                }
            }
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) = changed()
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) = changed()
        }
        am?.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        onDispose {
            am?.unregisterAudioDeviceCallback(cb)
            Live.release()
        }
    }

    // Actualizaciones (igual que Monitor)
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { Updater.fetch(ctx) }
            when {
                info == null -> if (manual) snack.showSnackbar("No se pudo consultar GitHub")
                Updater.isOffered(ctx, info, ignoreSkipped = manual) && (manual || !Updater.isSnoozed(info.build)) -> update = info
                else -> if (manual) snack.showSnackbar("Tienes la última versión")
            }
        }
    }
    LaunchedEffect(Unit) {
        reload()
        UpdateWorker.schedule(ctx)
        withContext(Dispatchers.IO) { runCatching { UpdateWorker.prepare(ctx) } }
        checkUpdate(false)
    }
    update?.let { info -> UpdateDialog(info, required = Updater.isRequired(ctx, info)) { update = null } }

    BackHandler(screen == Screen.EQ) { screen = Screen.MAIN }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text(if (screen == Screen.EQ) "Ecualizador" else "Dolby Atmos") },
                    navigationIcon = {
                        if (screen == Screen.EQ) IconButton(onClick = { screen = Screen.MAIN }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                        }
                    },
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                if (applying) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Spacer(Modifier.height(4.dp))
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
        ) {
            errMsg?.let {
                Column(Modifier.padding(16.dp)) {
                    PrefGroup { FooterText(it) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reload() }, Modifier.fillMaxWidth()) { Text("Reintentar") }
                }
            }
            val s = state ?: run {
                if (errMsg == null) Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val key = "$profile|$endpoint"
            val pe = s.pe[key] ?: emptyMap()
            fun b(k: String) = pe[k] == "1"
            fun i(k: String) = pe[k]?.toIntOrNull() ?: 0
            fun setPe(vararg kv: Pair<String, String>) {
                val st = state ?: return
                val cur = st.pe[key] ?: return
                state = st.copy(pe = st.pe + (key to (cur + kv)))
                kv.forEach { (k, _) -> if (k in Live.KEYS) dirty.add(k) else advDirty = true }
            }
            fun setTun(k: String, v: String) {
                val st = state ?: return
                val cur = st.tuning[endpoint] ?: return
                state = st.copy(tuning = st.tuning + (endpoint to (cur + (k to v))))
                advDirty = true
            }
            fun bool(v: Boolean) = if (v) "1" else "0"
            /** En vivo si hay control; si no, todo se trata como avanzado (reinicio). */
            fun change(vararg kv: Pair<String, String>) {
                setPe(*kv)
                if (live) commit() else applyAdvanced()
            }
            val on = s.dolbyOn
            val tun = s.tuning[endpoint] ?: emptyMap()
            val gains = s.geq[profile]

            if (screen == Screen.EQ) {
                if (gains != null) EqScreen(
                    gains = gains, freqs = s.geqFreqs, enabled = on && b("graphic_equalizer_enable"),
                    live = live && endpoint == activeEp, user = presets,
                    onEnabled = { change("graphic_equalizer_enable" to bool(it)) },
                    onChange = { g ->
                        dirty.add("geq")
                        state?.let { st ->
                            val ns = st.copy(geq = st.geq + (profile to g))
                            state = ns
                            // Suena mientras arrastras (si una aplicación sigue en curso, se salta)
                            if (live && endpoint == activeEp && liveBusy.compareAndSet(false, true)) {
                                val p = profile; val ep = endpoint
                                scope.launch(Dispatchers.IO) {
                                    try { Live.apply(ns, p, ep, setOf("geq")) } finally { liveBusy.set(false) }
                                }
                            }
                        }
                    },
                    onDone = { if (live) commit() else applyAdvanced() },
                    onSavePreset = { n -> userPresets.save(n, gains); presets = userPresets.all() },
                    onDeletePreset = { n -> userPresets.delete(n); presets = userPresets.all() },
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                MainSwitchBar("Usar Dolby Atmos", on) { v ->
                    state = state?.copy(dolbyOn = v); dirty.add("dolbyOn")
                    if (live) commit() else applyAdvanced()
                }
                Text(
                    if (live) "● Cambios en vivo, sin cortar la música" else "Sin control en vivo: los cambios reinician el audio un instante",
                    Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )

                PrefGroup {
                    ListPref("Perfil", DolbyStore.PROFILES, profile, enabled = on) {
                        profile = it; dirty.add("profile")
                        if (live) commit() else applyAdvanced()
                    }
                    PrefDivider()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text("Ajustes para", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val eps = listOf("speaker" to "Altavoz", "headphone" to "Auriculares")
                            eps.forEachIndexed { idx, (id, name) ->
                                SegmentedButton(
                                    selected = endpoint == id, onClick = { endpoint = id },
                                    shape = SegmentedButtonDefaults.itemShape(idx, eps.size),
                                ) { Text(if (id == activeEp) "$name ●" else name) }
                            }
                        }
                        Text(
                            when {
                                endpoint != activeEp -> "No está sonando por aquí: se guarda y se aplica cuando la uses"
                                endpoint == "headphone" -> "En uso · incluye cable, Bluetooth y USB-C"
                                else -> "En uso"
                            },
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                PrefGroup("Sonido") {
                    NavPref(
                        "Ecualizador",
                        when {
                            gains == null -> "No disponible en este perfil"
                            !b("graphic_equalizer_enable") -> "Desactivado"
                            else -> EqPresets.nameOf(gains, presets)
                        },
                        enabled = on && gains != null,
                    ) { screen = Screen.EQ }
                    PrefDivider()
                    val ieq = if (b("ieq_enable")) i("selected_ieq_preset_id") else 0
                    ListPref("Ecualizador inteligente", listOf(0 to "Desactivado") + DolbyStore.IEQ_PRESETS, ieq, enabled = on) { v ->
                        if (v == 0) change("ieq_enable" to "0")
                        else change("ieq_enable" to "1", "selected_ieq_preset_id" to v.toString())
                    }
                }

                PrefGroup("Efectos") {
                    SwitchPref("Realce de bajos", "Graves más potentes sin saturar", b("bass_enhancer_enable"), on) {
                        change("bass_enhancer_enable" to bool(it))
                    }
                    PrefDivider()
                    SwitchPref("Sonido envolvente", "Escena más amplia y envolvente", b("virtualizer_enable"), on) {
                        change("virtualizer_enable" to bool(it))
                    }
                    PrefDivider()
                    SwitchPref("Realce de diálogos", "Voces más claras en vídeos y películas", b("dialog_enhancer_enable"), on) {
                        change("dialog_enhancer_enable" to bool(it))
                    }
                    PrefDivider()
                    SwitchPref("Nivelador de volumen", "Volumen parejo entre canciones y apps", b("volume_leveler_enable"), on) {
                        change("volume_leveler_enable" to bool(it))
                    }
                }

                PrefGroup("Avanzado") {
                    FooterText("Estos ajustes necesitan reiniciar Dolby: se aplican con el botón y la música se corta un instante.")
                    SliderPref(
                        "Intensidad del realce de bajos", tun["bass_enhancer_boost"]?.toIntOrNull() ?: 0,
                        0, if (endpoint == "speaker") 192 else 288, 8, on, ::db,
                        { setTun("bass_enhancer_boost", it.toString()) }, {}
                    )
                    SliderPref(
                        "Frecuencia de corte de bajos", tun["bass_enhancer_cutoff_frequency"]?.toIntOrNull() ?: 160,
                        60, 300, 10, on, { "$it Hz" },
                        { setTun("bass_enhancer_cutoff_frequency", it.toString()) }, {}
                    )
                    SliderPref("Intensidad del ecualizador inteligente", i("ieq_amount"), 0, 16, 1, on, { "$it" },
                        { setPe("ieq_amount" to it.toString()) }, {})
                    SliderPref("Amplitud envolvente", i("surround_boost"), 0, 96, 4, on, ::db,
                        { setPe("surround_boost" to it.toString()) }, {})
                    SliderPref("Intensidad de diálogos", i("dialog_enhancer_amount"), 0, 16, 1, on, { "$it" },
                        { setPe("dialog_enhancer_amount" to it.toString()) }, {})
                    SliderPref("Intensidad del nivelador", i("volume_leveler_amount"), 0, 10, 1, on, { "$it" },
                        { setPe("volume_leveler_amount" to it.toString()) }, {})
                    SliderPref("Volumen máximo extra", i("volmax_boost"), 0, 144, 4, on, ::db,
                        { setPe("volmax_boost" to it.toString()) }, {})
                    if (endpoint == "speaker") SwitchPref(
                        "Bajo virtual", "Graves perceptibles en altavoces pequeños", b("virtual_bass_enable"), on
                    ) { setPe("virtual_bass_enable" to bool(it)) }
                    SwitchPref(
                        "Protección auditiva", "Limita el volumen alto sostenido", b("hearing_protection_enable"), on
                    ) { setPe("hearing_protection_enable" to bool(it)) }
                    Button(
                        onClick = { applyAdvanced() }, enabled = advDirty && !applying,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) { Text(if (advDirty) "Aplicar ajustes avanzados" else "Sin cambios pendientes") }
                }

                PrefGroup("General") {
                    NavPref("Restablecer valores", "Vuelve a como estaba la primera vez que abriste la app") { confirmReset = true }
                    PrefDivider()
                    NavPref("Actualizaciones", "Versión ${Updater.currentVersion(ctx)} · Buscar ahora") { checkUpdate(true) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("¿Restablecer valores?") },
        text = { Text("Dolby vuelve a la configuración que tenía la primera vez que abriste la app. La música se corta un instante.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    applying = true
                    val r = withContext(Dispatchers.IO) { runCatching { store.restoreOriginal() } }
                    applying = false
                    snack.showSnackbar(if (r.getOrNull()?.out?.contains("OK") == true) "Valores restablecidos" else "No se pudo restablecer")
                    reload()
                }
            }) { Text("Restablecer") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
    )
}

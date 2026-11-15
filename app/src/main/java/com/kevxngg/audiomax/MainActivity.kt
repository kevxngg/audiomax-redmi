package com.kevxngg.audiomax

import android.os.Bundle
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
    var endpoint by remember { mutableStateOf("speaker") }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var confirmReset by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Root.available()) error("Sin root: activa KernelSU/dfroot y concede root a AudioMax")
                    store.load()
                }
            }
            r.onSuccess { state = it; profile = it.activeProfile.coerceIn(0, 3); errMsg = null }
                .onFailure { errMsg = it.message }
        }
    }

    // Aplicado automático: espera a que dejes de tocar y aplica una sola vez
    var pending by remember { mutableStateOf<Job?>(null) }
    fun commit() {
        pending?.cancel()
        pending = scope.launch {
            delay(700)
            val s = state ?: return@launch
            applying = true
            val r = withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = profile)) } }
            applying = false
            if (r.getOrNull()?.out?.contains("OK") != true) {
                snack.showSnackbar("No se pudo aplicar: ${r.exceptionOrNull()?.message ?: r.getOrNull()?.out}")
                reload()
            }
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
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding()
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
            }
            fun setTun(k: String, v: String) {
                val st = state ?: return
                val cur = st.tuning[endpoint] ?: return
                state = st.copy(tuning = st.tuning + (endpoint to (cur + (k to v))))
            }
            fun bool(v: Boolean) = if (v) "1" else "0"
            val on = s.dolbyOn
            val tun = s.tuning[endpoint] ?: emptyMap()
            val gains = s.geq[profile]

            if (screen == Screen.EQ) {
                if (gains != null) EqScreen(
                    gains = gains, freqs = s.geqFreqs, enabled = on && b("graphic_equalizer_enable"),
                    onEnabled = { setPe("graphic_equalizer_enable" to bool(it)); commit() },
                    onChange = { g -> state?.let { st -> state = st.copy(geq = st.geq + (profile to g)) } },
                    onDone = { commit() },
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                MainSwitchBar("Usar Dolby Atmos", on) { v ->
                    state = state?.copy(dolbyOn = v); commit()
                }

                PrefGroup {
                    ListPref("Perfil", DolbyStore.PROFILES, profile, enabled = on) { profile = it; commit() }
                    PrefDivider()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(
                            "Ajustes para", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val eps = listOf("speaker" to "Altavoz", "headphone" to "Auriculares")
                            eps.forEachIndexed { idx, (id, name) ->
                                SegmentedButton(
                                    selected = endpoint == id, onClick = { endpoint = id },
                                    shape = SegmentedButtonDefaults.itemShape(idx, eps.size),
                                ) { Text(name) }
                            }
                        }
                        if (endpoint == "headphone") Text(
                            "Incluye cable, Bluetooth y USB-C",
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                PrefGroup("Sonido") {
                    NavPref(
                        "Ecualizador",
                        when {
                            gains == null -> "No disponible en este perfil"
                            !b("graphic_equalizer_enable") -> "Desactivado"
                            else -> EqPresets.nameOf(gains)
                        },
                        enabled = on && gains != null,
                    ) { screen = Screen.EQ }
                    PrefDivider()
                    val ieq = if (b("ieq_enable")) i("selected_ieq_preset_id") else 0
                    ListPref(
                        "Ecualizador inteligente",
                        listOf(0 to "Desactivado") + DolbyStore.IEQ_PRESETS, ieq, enabled = on
                    ) { v ->
                        if (v == 0) setPe("ieq_enable" to "0")
                        else setPe("ieq_enable" to "1", "selected_ieq_preset_id" to v.toString())
                        commit()
                    }
                    if (ieq != 0) SliderPref(
                        "Intensidad", i("ieq_amount"), 0, 16, 1, on, { "$it" },
                        { setPe("ieq_amount" to it.toString()) }, { commit() }
                    )
                }

                PrefGroup("Graves") {
                    SwitchPref(
                        "Realce de bajos", "Refuerza los graves sin saturar",
                        b("bass_enhancer_enable"), on
                    ) { setPe("bass_enhancer_enable" to bool(it)); commit() }
                    val bassOn = on && b("bass_enhancer_enable")
                    SliderPref(
                        "Nivel de bajos", tun["bass_enhancer_boost"]?.toIntOrNull() ?: 0,
                        0, if (endpoint == "speaker") 192 else 288, 8, bassOn, ::db,
                        { setTun("bass_enhancer_boost", it.toString()) }, { commit() }
                    )
                    SliderPref(
                        "Frecuencia de corte", tun["bass_enhancer_cutoff_frequency"]?.toIntOrNull() ?: 160,
                        60, 300, 10, bassOn, { "$it Hz" },
                        { setTun("bass_enhancer_cutoff_frequency", it.toString()) }, { commit() }
                    )
                    if (endpoint == "speaker") {
                        PrefDivider()
                        SwitchPref(
                            "Bajo virtual", "Hace que los graves se perciban en altavoces pequeños",
                            b("virtual_bass_enable"), on
                        ) { setPe("virtual_bass_enable" to bool(it)); commit() }
                    }
                }

                PrefGroup("Espacio y voz") {
                    SwitchPref("Sonido envolvente", "Escena más amplia y envolvente", b("virtualizer_enable"), on) {
                        setPe("virtualizer_enable" to bool(it)); commit()
                    }
                    SliderPref(
                        "Amplitud estéreo", i("surround_boost"), 0, 96, 4, on && b("virtualizer_enable"), ::db,
                        { setPe("surround_boost" to it.toString()) }, { commit() }
                    )
                    PrefDivider()
                    SwitchPref("Realce de diálogos", "Voces más claras en vídeos y películas", b("dialog_enhancer_enable"), on) {
                        setPe("dialog_enhancer_enable" to bool(it)); commit()
                    }
                    if (b("dialog_enhancer_enable")) SliderPref(
                        "Intensidad", i("dialog_enhancer_amount"), 0, 16, 1, on, { "$it" },
                        { setPe("dialog_enhancer_amount" to it.toString()) }, { commit() }
                    )
                }

                PrefGroup("Volumen") {
                    SwitchPref("Nivelador de volumen", "Mantiene un volumen parejo entre canciones y apps", b("volume_leveler_enable"), on) {
                        setPe("volume_leveler_enable" to bool(it)); commit()
                    }
                    if (b("volume_leveler_enable")) SliderPref(
                        "Intensidad", i("volume_leveler_amount"), 0, 10, 1, on, { "$it" },
                        { setPe("volume_leveler_amount" to it.toString()) }, { commit() }
                    )
                    PrefDivider()
                    SliderPref(
                        "Volumen máximo extra", i("volmax_boost"), 0, 144, 4, on, ::db,
                        { setPe("volmax_boost" to it.toString()) }, { commit() }
                    )
                }

                PrefGroup("General") {
                    NavPref("Restablecer valores", "Vuelve a como estaba al abrir AudioMax por primera vez") {
                        confirmReset = true
                    }
                    PrefDivider()
                    NavPref("Actualizaciones", "Versión ${Updater.currentVersion(ctx)} · Buscar ahora") {
                        checkUpdate(true)
                    }
                }
                FooterText("Los cambios se aplican solos. El audio se corta un instante mientras Dolby se reinicia.")
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("¿Restablecer valores?") },
        text = { Text("Dolby vuelve a la configuración que tenía la primera vez que abriste AudioMax.") },
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

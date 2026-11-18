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
import androidx.compose.ui.res.stringResource
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

private enum class Screen { MAIN, EQ, ABOUT }

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
    val tone = remember { ToneStore(ctx) }
    val dirty = remember { mutableSetOf<String>() }
    val userPresets = remember { UserPresets(ctx) }
    var presets by remember { mutableStateOf(userPresets.all()) }
    var keeperOn by remember { mutableStateOf(KeeperService.enabled(ctx)) }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!Root.available()) error(ctx.getString(R.string.err_no_root))
                    val st = store.load()
                    live = Live.available
                    st
                }
            }
            r.onSuccess { state = it; profile = it.activeProfile.coerceIn(0, 7); errMsg = null }
                .onFailure { errMsg = it.message }
        }
    }

    // Save to the database without restarting (so it survives a phone reboot)
    var persistJob by remember { mutableStateOf<Job?>(null) }
    var deferredPending by remember { mutableStateOf(false) }
    fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1200)
            val s = state ?: return@launch
            withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = profile), restart = false) } }
            SyncWorker.schedule(ctx, soon = deferredPending)
            deferredPending = false
        }
    }

    /** Applies instantly whatever can be applied live. Without live control, falls back to a restart. */
    fun commit() {
        val s = state ?: return
        val keys = dirty.toSet()
        dirty.clear()
        val p = profile
        val ep = endpoint
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // The output that is not playing is saved and synced later
                if (ep != Live.activeEndpoint(ctx) && keys.none { it == "dolbyOn" || it == "profile" }) true
                else Live.apply(s.copy(activeProfile = p), p, ep, keys)
            }
            if (ok) persist() else {
                applying = true
                val r = withContext(Dispatchers.IO) { runCatching { store.save(s.copy(activeProfile = p), restart = true) } }
                applying = false
                if (r.getOrNull()?.out?.contains("OK") != true) snack.showSnackbar(ctx.getString(R.string.apply_failed))
            }
        }
    }

    /** Settings Dolby doesn't allow changing live: saved and applied automatically on pause. */
    fun deferred() {
        deferredPending = true
        persist()
    }

    // Follow the output in use and reapply the settings (Dolby reloads the old ones on change)
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

    // Updates (same as Monitor)
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { Updater.fetch(ctx) }
            when {
                info == null -> if (manual) snack.showSnackbar(ctx.getString(R.string.update_check_failed))
                Updater.isOffered(ctx, info, ignoreSkipped = manual) && (manual || !Updater.isSnoozed(info.build)) -> update = info
                else -> if (manual) snack.showSnackbar(ctx.getString(R.string.update_latest))
            }
        }
    }
    LaunchedEffect(Unit) {
        reload()
        KeeperService.start(ctx)
        UpdateWorker.schedule(ctx)
        withContext(Dispatchers.IO) { runCatching { UpdateWorker.prepare(ctx) } }
        checkUpdate(false)
    }
    update?.let { info -> UpdateDialog(info, required = Updater.isRequired(ctx, info)) { update = null } }

    BackHandler(screen != Screen.MAIN) { screen = Screen.MAIN }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        Text(stringResource(when (screen) {
                            Screen.EQ -> R.string.title_eq; Screen.ABOUT -> R.string.title_about; else -> R.string.app_name
                        }))
                    },
                    navigationIcon = {
                        if (screen != Screen.MAIN) IconButton(onClick = { screen = Screen.MAIN }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
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
                    Button(onClick = { reload() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
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
            fun bool(v: Boolean) = if (v) "1" else "0"
            fun done() { if (live) commit() else persist() }
            /** Changes columns: live if possible; otherwise saved and applied on pause. */
            fun change(vararg kv: Pair<String, String>, now: Boolean = true) {
                val st = state ?: return
                val cur = st.pe[key] ?: return
                state = st.copy(pe = st.pe + (key to (cur + kv)))
                dirty.addAll(kv.map { it.first }.filter { it in Live.KEYS })
                if (kv.any { it.first in Live.DEFERRED }) deferredPending = true
                if (now) done()
            }
            val pr = s.prof[profile] ?: emptyMap()
            fun pb(k: String) = pr[k] == "1"
            fun pi(k: String) = pr[k]?.toIntOrNull() ?: 0
            /** Profile settings (profiles table): applied when the music is paused. */
            fun setProf(k: String, v: String, now: Boolean = true) {
                val st = state ?: return
                val cur = st.prof[profile] ?: return
                state = st.copy(prof = st.prof + (profile to (cur + (k to v))))
                deferredPending = true
                if (now) deferred()
            }
            fun setTun(k: String, v: String) {
                val st = state ?: return
                val cur = st.tuning[endpoint] ?: return
                state = st.copy(tuning = st.tuning + (endpoint to (cur + (k to v))))
                deferredPending = true
            }
            /** Changes the equalizer curve and sends it live while the control is moving. */
            fun setGeq(g: List<Int>) {
                val st = state ?: return
                val ns = st.copy(geq = st.geq + (profile to g))
                state = ns
                dirty.add("geq")
                if (live && endpoint == activeEp && liveBusy.compareAndSet(false, true)) {
                    val p = profile
                    val ep = endpoint
                    scope.launch(Dispatchers.IO) {
                        try { Live.apply(ns, p, ep, setOf("geq")) } finally { liveBusy.set(false) }
                    }
                }
            }
            /** Extra gain or bass on top of the leveler makes the volume rise and then fall back: turn the leveler off. */
            fun levelerOffForGain(v: Int) {
                if (v > 0 && b("volume_leveler_enable")) {
                    change("volume_leveler_enable" to "0", now = false)
                    scope.launch { snack.showSnackbar(ctx.getString(R.string.leveler_auto_off)) }
                }
            }
            /** Removes what compresses or colors the music in this profile; keeps EQ, bass and surround. */
            fun hifi() {
                change("volume_leveler_enable" to "0", "ieq_enable" to "0", "dialog_enhancer_enable" to "0", now = false)
                for (k in listOf(
                    "reverb_suppression_enable", "mi_ieq_steering_enable", "mi_adaptive_virtualizer_steering_enable",
                    "mi_dv_leveler_steering_enable", "mi_surround_compressor_steering_enable", "volume_modeler_enable",
                )) setProf(k, "0", now = false)
                done()
                deferred()
                scope.launch { snack.showSnackbar(ctx.getString(R.string.hifi_done)) }
            }
            val on = s.dolbyOn
            val tun = s.tuning[endpoint] ?: emptyMap()
            val gains = s.geq[profile]

            if (screen == Screen.ABOUT) {
                AboutScreen()
                return@Column
            }
            if (screen == Screen.EQ) {
                if (gains != null) EqScreen(
                    gains = gains, freqs = s.geqFreqs, enabled = on && b("graphic_equalizer_enable"),
                    live = live && endpoint == activeEp, user = presets,
                    onEnabled = { change("graphic_equalizer_enable" to bool(it)) },
                    onChange = { g -> setGeq(g) },
                    onDone = { done() },
                    onSavePreset = { n, g -> userPresets.save(n, g); presets = userPresets.all() },
                    onDeletePreset = { n -> userPresets.delete(n); presets = userPresets.all() },
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                MainSwitchBar(stringResource(R.string.use_dolby), on) { v ->
                    state = state?.copy(dolbyOn = v)
                    dirty.add("dolbyOn")
                    done()
                }
                Text(
                    stringResource(if (live) R.string.live_on else R.string.live_off),
                    Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )

                PrefGroup {
                    ListPref(stringResource(R.string.profile), labels(DolbyStore.PROFILES), profile, enabled = on) {
                        profile = it
                        dirty.add("profile")
                        done()
                    }
                    PrefDivider()
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.settings_for), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val eps = listOf("speaker" to R.string.endpoint_speaker, "headphone" to R.string.endpoint_headphone)
                            eps.forEachIndexed { idx, (id, res) ->
                                val name = stringResource(res)
                                SegmentedButton(
                                    selected = endpoint == id, onClick = { endpoint = id },
                                    shape = SegmentedButtonDefaults.itemShape(idx, eps.size),
                                ) { Text(if (id == activeEp) stringResource(R.string.endpoint_active_mark, name) else name) }
                            }
                        }
                        Text(
                            stringResource(
                                when {
                                    endpoint != activeEp -> R.string.endpoint_not_playing
                                    endpoint == "headphone" -> R.string.endpoint_in_use_headphone
                                    else -> R.string.endpoint_in_use
                                }
                            ),
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    PrefDivider()
                    NavPref(stringResource(R.string.hifi), stringResource(R.string.hifi_summary), enabled = on) { hifi() }
                }

                PrefGroup(stringResource(R.string.group_sound)) {
                    NavPref(
                        stringResource(R.string.title_eq),
                        when {
                            gains == null -> stringResource(R.string.eq_unavailable)
                            !b("graphic_equalizer_enable") -> stringResource(R.string.off)
                            else -> EqPresets.nameOf(ctx, gains, presets)
                        },
                        enabled = on && gains != null,
                    ) { screen = Screen.EQ }
                    PrefDivider()
                    val ieq = if (b("ieq_enable")) i("selected_ieq_preset_id") else 0
                    ListPref(stringResource(R.string.ieq), labels(listOf(0 to R.string.off) + DolbyStore.IEQ_PRESETS), ieq, enabled = on) { v ->
                        if (v == 0) change("ieq_enable" to "0")
                        else change("ieq_enable" to "1", "selected_ieq_preset_id" to v.toString())
                    }
                    if (ieq != 0) SliderPref(
                        stringResource(R.string.intensity), i("ieq_amount"), 0, 16, 1, on, { "$it" },
                        { change("ieq_amount" to it.toString(), now = false) }, { done() }
                    )
                }

                PrefGroup(stringResource(R.string.group_bass)) {
                    SwitchPref(stringResource(R.string.bass_enhancer), stringResource(R.string.bass_enhancer_summary), b("bass_enhancer_enable"), on) {
                        change("bass_enhancer_enable" to bool(it))
                    }
                    if (b("bass_enhancer_enable") && gains != null) {
                        val lvl = tone.bass(profile)
                        val curve = tone.curve(profile)
                        SliderPref(
                            stringResource(R.string.bass_level), lvl, 0, 12, 1, on, { "+$it dB" },
                            { v ->
                                val old = tone.bass(profile)
                                val g = state?.geq?.get(profile)
                                if (v != old && g != null) {
                                    setGeq(ToneStore.withBass(ToneStore.withBass(g, old, curve, -1), v, curve, 1))
                                    tone.set(profile, bass = v)
                                    if (old == 0) levelerOffForGain(v)
                                }
                            },
                            { done() },
                        )
                        ListPref(stringResource(R.string.bass_type), labels(ToneStore.CURVES), curve, enabled = on) { c ->
                            val g = state?.geq?.get(profile)
                            if (c != curve && g != null) {
                                setGeq(ToneStore.withBass(ToneStore.withBass(g, lvl, curve, -1), lvl, c, 1))
                                tone.set(profile, curve = c)
                                done()
                            }
                        }
                        // "Virtual bass" removed: measured on the phone, it does not change the sound (speaker or headphones)
                        SliderPref(
                            stringResource(R.string.dolby_bass_boost), tun["bass_enhancer_boost"]?.toIntOrNull() ?: 0,
                            0, if (endpoint == "speaker") 192 else 288, 8, on, ::db,
                            { setTun("bass_enhancer_boost", it.toString()) }, { deferred() }
                        )
                        FooterText(stringResource(R.string.bass_footer))
                    }
                }

                // Width, angle, distance and background ducking removed: measured on the phone, they don't change the sound.
                // The virtualizer only acts on headphones (on the speaker it changes nothing).
                PrefGroup(stringResource(R.string.group_space_voice)) {
                    if (endpoint == "headphone") {
                        SwitchPref(stringResource(R.string.surround), stringResource(R.string.surround_summary), b("virtualizer_enable"), on) {
                            change("virtualizer_enable" to bool(it))
                        }
                        if (b("virtualizer_enable")) {
                            ListPref(
                                stringResource(R.string.virtualizer_mode),
                                labels(listOf(1 to R.string.virtualizer_standard, 2 to R.string.virtualizer_immersive)),
                                pi("headphone_virtualizer_mode").coerceIn(1, 2), enabled = on
                            ) { setProf("headphone_virtualizer_mode", it.toString()) }
                            FooterText(stringResource(R.string.virtualizer_footer))
                        }
                        PrefDivider()
                    }
                    SwitchPref(stringResource(R.string.dialogue), stringResource(R.string.dialogue_summary), b("dialog_enhancer_enable"), on) {
                        change("dialog_enhancer_enable" to bool(it))
                    }
                    if (b("dialog_enhancer_enable")) SliderPref(
                        stringResource(R.string.intensity), i("dialog_enhancer_amount"), 0, 16, 1, on, { "$it" },
                        { change("dialog_enhancer_amount" to it.toString(), now = false) }, { done() }
                    )
                    PrefDivider()
                    SwitchPref(
                        stringResource(R.string.reverb), stringResource(R.string.reverb_summary), pb("reverb_suppression_enable"), on
                    ) { setProf("reverb_suppression_enable", bool(it)) }
                    if (pb("reverb_suppression_enable")) SliderPref(
                        stringResource(R.string.intensity), pi("reverb_suppression_amount"), 0, 16, 1, on, { "$it" },
                        { setProf("reverb_suppression_amount", it.toString(), now = false) }, { deferred() }
                    )
                }

                PrefGroup(stringResource(R.string.group_volume)) {
                    SwitchPref(stringResource(R.string.leveler), stringResource(R.string.leveler_summary), b("volume_leveler_enable"), on) {
                        change("volume_leveler_enable" to bool(it))
                    }
                    if (b("volume_leveler_enable")) {
                        SliderPref(
                            stringResource(R.string.intensity), i("volume_leveler_amount"), 0, 10, 1, on, { "$it" },
                            { change("volume_leveler_amount" to it.toString(), now = false) }, { done() }
                        )
                        FooterText(stringResource(R.string.leveler_warning))
                    }
                    PrefDivider()
                    if (gains != null) SliderPref(
                        stringResource(R.string.extra_gain), tone.gain(profile), 0, 6, 1, on, { "+$it dB" },
                        { v ->
                            val old = tone.gain(profile)
                            val g = state?.geq?.get(profile)
                            if (v != old && g != null) {
                                setGeq(ToneStore.withGain(ToneStore.withGain(g, old, -1), v, 1))
                                tone.set(profile, gain = v)
                                if (old == 0) levelerOffForGain(v)
                            }
                        },
                        { done() },
                    )
                    // Dolby max volume and hearing protection removed: measured on the phone, they don't change the sound
                    FooterText(stringResource(R.string.extra_gain_footer))
                }

                // Intelligent leveler, surround compressor and volume modeler removed: they don't change the sound
                PrefGroup(stringResource(R.string.group_intelligent)) {
                    if (endpoint == "headphone") {
                        SwitchPref(
                            stringResource(R.string.adaptive_virtualizer), stringResource(R.string.adaptive_virtualizer_summary),
                            pb("mi_adaptive_virtualizer_steering_enable"), on
                        ) { setProf("mi_adaptive_virtualizer_steering_enable", bool(it)) }
                        PrefDivider()
                    }
                    SwitchPref(
                        stringResource(R.string.adaptive_eq), stringResource(R.string.adaptive_eq_summary),
                        pb("mi_ieq_steering_enable"), on
                    ) { setProf("mi_ieq_steering_enable", bool(it)) }
                    FooterText(stringResource(R.string.intelligent_footer))
                }

                PrefGroup(stringResource(R.string.group_general)) {
                    SwitchPref(stringResource(R.string.keeper_pref), stringResource(R.string.keeper_pref_summary), keeperOn, true) {
                        keeperOn = it
                        KeeperService.setEnabled(ctx, it)
                    }
                    PrefDivider()
                    NavPref(stringResource(R.string.reset_values), stringResource(R.string.reset_values_summary)) { confirmReset = true }
                    PrefDivider()
                    NavPref(stringResource(R.string.updates), stringResource(R.string.updates_summary, Updater.currentVersion(ctx))) { checkUpdate(true) }
                    PrefDivider()
                    NavPref(stringResource(R.string.title_about), stringResource(R.string.about_summary)) { screen = Screen.ABOUT }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.reset_confirm_title)) },
        text = { Text(stringResource(R.string.reset_confirm_text)) },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    applying = true
                    val r = withContext(Dispatchers.IO) { runCatching { store.restoreOriginal() } }
                    applying = false
                    snack.showSnackbar(
                        ctx.getString(if (r.getOrNull()?.out?.contains("OK") == true) R.string.reset_done else R.string.reset_failed)
                    )
                    reload()
                }
            }) { Text(stringResource(R.string.reset)) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

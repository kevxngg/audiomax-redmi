package com.kevxngg.audiomax

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Cambios en vivo sobre el efecto Dolby (sin reiniciar el audio).
 * Probado en el Redmi Note 14 Pro 4G: la app obtiene el control del DAP en la sesión global.
 */
object Live {
    private const val TAG = "AudioMaxLive"

    /** Columnas que se pueden aplicar en vivo. El resto necesita reiniciar Dolby. */
    val KEYS = setOf(
        "dolbyOn", "profile", "geq", "graphic_equalizer_enable",
        "bass_enhancer_enable", "virtualizer_enable", "volume_leveler_enable",
        "ieq_enable", "selected_ieq_preset_id", "dialog_enhancer_enable",
    )

    private var fx: DolbyEffect? = null

    @Synchronized
    fun effect(): DolbyEffect? {
        val cur = fx
        if (cur != null && cur.hasControl) return cur
        cur?.release()
        fx = runCatching { DolbyEffect.open(100) }.onFailure { Log.w(TAG, "open: ${it.message}") }.getOrNull()
        return fx
    }

    val available: Boolean get() = effect() != null

    @Synchronized
    fun release() {
        fx?.release()
        fx = null
    }

    /** Salida por la que suena la música ahora: "headphone" (cable, BT, USB) o "speaker". */
    fun activeEndpoint(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "speaker"
        val hp = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return if (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in hp }) "headphone" else "speaker"
    }

    /**
     * Aplica en vivo las claves cambiadas. Devuelve false si algo no se pudo aplicar
     * (entonces hay que usar el reinicio).
     */
    @Synchronized
    fun apply(ctx: Context, s: DolbyStore.State, profile: Int, endpoint: String, keys: Set<String>): Boolean {
        if (!keys.all { it in KEYS }) return false
        val f = effect() ?: return false
        return try {
            if ("dolbyOn" in keys) f.dsOn = s.dolbyOn
            if ("profile" in keys) f.profile = profile
            val rest = keys - setOf("dolbyOn", "profile")
            if (rest.isNotEmpty()) {
                // Los parámetros del perfil afectan a la salida activa; otra salida necesita reinicio
                if (endpoint != activeEndpoint(ctx)) return false
                val pe = s.pe["$profile|$endpoint"] ?: return false
                fun on(k: String) = pe[k] == "1"
                for (k in rest) when (k) {
                    "bass_enhancer_enable" -> f.set(DolbyEffect.Param.BASS_ENHANCER_ENABLE, on(k), profile)
                    "virtualizer_enable" -> f.set(
                        if (endpoint == "headphone") DolbyEffect.Param.HEADPHONE_VIRTUALIZER
                        else DolbyEffect.Param.SPEAKER_VIRTUALIZER, on(k), profile
                    )
                    "volume_leveler_enable" -> f.set(DolbyEffect.Param.VOLUME_LEVELER_ENABLE, on(k), profile)
                    "ieq_enable", "selected_ieq_preset_id" -> f.set(
                        DolbyEffect.Param.IEQ_PRESET,
                        if (on("ieq_enable")) pe["selected_ieq_preset_id"]?.toIntOrNull() ?: 0 else 0, profile
                    )
                    "dialog_enhancer_enable" -> f.set(DolbyEffect.Param.DIALOGUE_ENHANCER_ENABLE, on(k), profile)
                    "geq", "graphic_equalizer_enable" -> {
                        val g = s.geq[profile] ?: List(20) { 0 }
                        f.set(
                            DolbyEffect.Param.GEQ_BAND_GAINS,
                            if (on("graphic_equalizer_enable")) g.toIntArray() else IntArray(g.size), profile
                        )
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "apply: ${e.message}")
            false
        }
    }
}

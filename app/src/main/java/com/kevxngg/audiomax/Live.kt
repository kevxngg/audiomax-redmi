package com.kevxngg.audiomax

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Live changes on the Dolby effect (without restarting the audio).
 * Tested on the Redmi Note 14 Pro 4G: the app gets control of the DAP on the global session.
 */
object Live {
    private const val TAG = "AudioMaxLive"

    /** Columns applied live. The rest need a Dolby restart (Advanced section). */
    val KEYS = setOf(
        "dolbyOn", "profile", "geq", "graphic_equalizer_enable",
        "bass_enhancer_enable", "virtualizer_enable", "volume_leveler_enable",
        "ieq_enable", "selected_ieq_preset_id", "dialog_enhancer_enable",
        "ieq_amount", "dialog_enhancer_amount", "volume_leveler_amount", "virtual_bass_enable",
    )

    /** Cannot be applied live: saved and applied automatically when no music is playing. */
    val DEFERRED = setOf(
        "tuning", "prof", "volmax_boost", "surround_boost", "hearing_protection_enable",
        "dialog_enhancer_ducking", "headphone_virtualizer_steerer_source_distance",
    )

    private val PROFILE_KEYS = KEYS - setOf("dolbyOn", "profile")

    private var fx: DolbyEffect? = null

    /** Returns a live effect; if the audio was restarted, it reopens it. */
    @Synchronized
    fun effect(): DolbyEffect? {
        fx?.let { f ->
            if (f.hasControl && runCatching { f.dsOn }.isSuccess) return f
            f.release()
        }
        fx = runCatching { DolbyEffect.open(100) }.onFailure { Log.w(TAG, "open: ${it.message}") }.getOrNull()
        return fx
    }

    val available: Boolean get() = effect() != null

    @Synchronized
    fun release() {
        fx?.release()
        fx = null
    }

    /** Output the music is currently playing through: "headphone" (wired, BT, USB) or "speaker". */
    fun activeEndpoint(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "speaker"
        val hp = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return if (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in hp }) "headphone" else "speaker"
    }

    /** Applies the changed keys live (retries once by reopening the effect). */
    @Synchronized
    fun apply(s: DolbyStore.State, profile: Int, endpoint: String, keys: Set<String>): Boolean {
        val k = keys.filter { it in KEYS }.toSet()
        if (k.isEmpty()) return true
        repeat(2) {
            val f = effect() ?: return false
            try {
                push(f, s, profile, endpoint, k)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "apply: ${e.message}")
                fx?.release(); fx = null
            }
        }
        return false
    }

    /** Re-sends everything (when the output changes, Dolby reloads its old values). */
    fun applyAll(s: DolbyStore.State, profile: Int, endpoint: String) =
        apply(s, profile, endpoint, setOf("dolbyOn") + PROFILE_KEYS)

    private fun push(f: DolbyEffect, s: DolbyStore.State, profile: Int, endpoint: String, keys: Set<String>) {
        if ("dolbyOn" in keys) f.dsOn = s.dolbyOn
        if ("profile" in keys) f.profile = profile
        val pe = s.pe["$profile|$endpoint"] ?: return
        fun on(c: String) = pe[c] == "1"
        val done = mutableSetOf<String>()
        for (c in keys) when (c) {
            "bass_enhancer_enable" -> f.set(DolbyEffect.Param.BASS_ENHANCER_ENABLE, on(c), profile)
            "virtualizer_enable" -> f.set(
                if (endpoint == "headphone") DolbyEffect.Param.HEADPHONE_VIRTUALIZER
                else DolbyEffect.Param.SPEAKER_VIRTUALIZER, on(c), profile
            )
            "volume_leveler_enable" -> f.set(DolbyEffect.Param.VOLUME_LEVELER_ENABLE, on(c), profile)
            "dialog_enhancer_enable" -> f.set(DolbyEffect.Param.DIALOGUE_ENHANCER_ENABLE, on(c), profile)
            "ieq_amount" -> f.set(DolbyEffect.Param.IEQ_AMOUNT, pe[c]?.toIntOrNull() ?: 0, profile)
            "dialog_enhancer_amount" -> f.set(DolbyEffect.Param.DIALOGUE_ENHANCER_AMOUNT, pe[c]?.toIntOrNull() ?: 0, profile)
            "volume_leveler_amount" -> f.set(DolbyEffect.Param.VOLUME_LEVELER_AMOUNT, pe[c]?.toIntOrNull() ?: 0, profile)
            "virtual_bass_enable" -> f.set(DolbyEffect.Param.VIRTUAL_BASS_ENABLE, on(c), profile)
            "ieq_enable", "selected_ieq_preset_id" -> if (done.add("ieq")) f.set(
                DolbyEffect.Param.IEQ_PRESET,
                if (on("ieq_enable")) pe["selected_ieq_preset_id"]?.toIntOrNull() ?: 0 else 0, profile
            )
            "geq", "graphic_equalizer_enable" -> if (done.add("geq")) {
                val g = s.geq[profile] ?: List(20) { 0 }
                f.set(
                    DolbyEffect.Param.GEQ_BAND_GAINS,
                    if (on("graphic_equalizer_enable")) g.toIntArray() else IntArray(g.size), profile
                )
            }
        }
    }
}

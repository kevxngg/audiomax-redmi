/*
 * Control en vivo del efecto Dolby (DAP) del sistema.
 * Basado en DolbyAudioEffect de Paranoid Android / Lunaris AOSP (Apache-2.0).
 */
package com.kevxngg.audiomax

import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.UUID

class DolbyEffect private constructor(private val fx: AudioEffect) {

    /** Parámetros de perfil del DAP (id, número de valores). */
    enum class Param(val id: Int, val length: Int = 1) {
        HEADPHONE_VIRTUALIZER(101),
        SPEAKER_VIRTUALIZER(102),
        VOLUME_LEVELER_ENABLE(103),
        IEQ_PRESET(104),
        DIALOGUE_ENHANCER_ENABLE(105),
        DIALOGUE_ENHANCER_AMOUNT(108),
        GEQ_BAND_GAINS(110, 20),
        BASS_ENHANCER_ENABLE(111),
        STEREO_WIDENING_AMOUNT(113),
    }

    val hasControl: Boolean get() = runCatching { fx.hasControl() }.getOrDefault(false)

    var dsOn: Boolean
        get() = getInt(PARAM_ENABLE) == 1
        set(v) {
            setInt(PARAM_ENABLE, if (v) 1 else 0)
            runCatching { fx.enabled = v }
        }

    var profile: Int
        get() = getInt(PARAM_PROFILE)
        set(v) = setInt(PARAM_PROFILE, v)

    private fun sp(k: ByteArray, v: ByteArray): Int = SET.invoke(fx, k, v) as Int
    private fun gp(k: ByteArray, v: ByteArray): Int = GET.invoke(fx, k, v) as Int

    private fun check(status: Int, what: String) {
        if (status < 0) throw IllegalStateException("$what -> $status")
    }

    private fun setInt(param: Int, value: Int) {
        val buf = ByteArray(12)
        put(param, buf, 0); put(1, buf, 4); put(value, buf, 8)
        check(sp(ib(PARAM_CPDP_VALUES), buf), "setInt($param)")
    }

    private fun getInt(param: Int): Int {
        val buf = ByteArray(12)
        put(param, buf, 0)
        check(gp(ib(PARAM_CPDP_VALUES + param), buf), "getInt($param)")
        return int(buf, 0)
    }

    fun set(p: Param, values: IntArray, profile: Int) {
        val buf = ByteArray((values.size + 4) * 4)
        put(PARAM_SET_PROFILE_PARAMETER, buf, 0)
        put(values.size + 1, buf, 4)
        put(profile, buf, 8)
        put(p.id, buf, 12)
        values.forEachIndexed { i, v -> put(v, buf, 16 + i * 4) }
        check(sp(ib(PARAM_CPDP_VALUES), buf), "set($p)")
    }

    fun set(p: Param, value: Int, profile: Int) = set(p, intArrayOf(value), profile)
    fun set(p: Param, on: Boolean, profile: Int) = set(p, if (on) 1 else 0, profile)

    fun get(p: Param, profile: Int): IntArray {
        val buf = ByteArray((p.length + 2) * 4)
        val id = (p.id shl 16) + (profile shl 8) + PARAM_GET_PROFILE_PARAMETER
        check(gp(ib(id), buf), "get($p)")
        return IntArray(minOf(p.length, buf.size / 4)) { int(buf, it * 4) }
    }

    /** Lectura/escritura por id numérico (para descubrir parámetros). */
    fun getRaw(id: Int, profile: Int, length: Int): IntArray {
        val buf = ByteArray((length + 2) * 4)
        val p = (id shl 16) + (profile shl 8) + PARAM_GET_PROFILE_PARAMETER
        check(gp(ib(p), buf), "getRaw($id)")
        return IntArray(length) { int(buf, it * 4) }
    }

    fun setRaw(id: Int, values: IntArray, profile: Int) {
        val buf = ByteArray((values.size + 4) * 4)
        put(PARAM_SET_PROFILE_PARAMETER, buf, 0)
        put(values.size + 1, buf, 4)
        put(profile, buf, 8)
        put(id, buf, 12)
        values.forEachIndexed { i, v -> put(v, buf, 16 + i * 4) }
        check(sp(ib(PARAM_CPDP_VALUES), buf), "setRaw($id)")
    }

    fun release() = runCatching { fx.release() }

    companion object {
        private const val TAG = "AudioMaxFx"
        private val TYPE_DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
        private val TYPE_NULL = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
        private const val PARAM_ENABLE = 0
        private const val PARAM_CPDP_VALUES = 5
        private const val PARAM_PROFILE = 0xA000000
        private const val PARAM_SET_PROFILE_PARAMETER = 0x1000000
        private const val PARAM_GET_PROFILE_PARAMETER = 0x1000005

        /** Crea el efecto en la sesión global (0). El constructor es interno de Android. */
        fun open(priority: Int = 100): DolbyEffect {
            if (Build.VERSION.SDK_INT >= 28) runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            val c = AudioEffect::class.java.getDeclaredConstructor(
                UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            c.isAccessible = true
            val fx = c.newInstance(TYPE_NULL, TYPE_DAP, priority, 0)
            Log.i(TAG, "DAP abierto, control=${fx.hasControl()}")
            return DolbyEffect(fx)
        }

        private val SET by lazy {
            AudioEffect::class.java.getDeclaredMethod("setParameter", ByteArray::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
        }
        private val GET by lazy {
            AudioEffect::class.java.getDeclaredMethod("getParameter", ByteArray::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
        }

        private fun ib(v: Int) = ByteArray(4).also { put(v, it, 0) }

        private fun put(v: Int, b: ByteArray, i: Int) {
            b[i] = (v and 0xff).toByte()
            b[i + 1] = ((v ushr 8) and 0xff).toByte()
            b[i + 2] = ((v ushr 16) and 0xff).toByte()
            b[i + 3] = ((v ushr 24) and 0xff).toByte()
        }

        private fun int(b: ByteArray, i: Int): Int =
            (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    }
}

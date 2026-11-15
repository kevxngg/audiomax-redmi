package com.kevxngg.audiomax

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Lee y escribe la configuración del Dolby nativo (DAP) del Redmi Note 14 Pro 4G.
 * El servicio dms-hal guarda todo en /data/vendor/dolby/dax_sqlite3.db; se edita una copia
 * y luego se reemplaza el original reiniciando dms-hal y audioserver.
 * Ganancias de Dolby en 1/16 dB.
 */
class DolbyStore(private val ctx: Context) {

    companion object {
        const val DB = "/data/vendor/dolby/dax_sqlite3.db"

        val PROFILES = listOf(
            0 to "Dinámico", 1 to "Película", 2 to "Música", 3 to "Voz",
            4 to "Movilidad", 5 to "En movimiento", 6 to "Trayecto", 7 to "Viaje",
        )
        val ENDPOINTS = listOf("speaker" to "Altavoz", "headphone" to "Auriculares · BT · USB")
        val IEQ_PRESETS = listOf(1 to "Detallado", 2 to "Equilibrado", 3 to "Cálido")

        /** Columnas de profile_endpoints que maneja la app. */
        val PE_KEYS = listOf(
            "bass_enhancer_enable", "virtual_bass_enable",
            "ieq_enable", "selected_ieq_preset_id", "ieq_amount",
            "graphic_equalizer_enable",
            "virtualizer_enable", "surround_boost",
            "dialog_enhancer_enable", "dialog_enhancer_amount",
            "volume_leveler_enable", "volume_leveler_amount",
            "volmax_boost", "hearing_protection_enable",
            "dialog_enhancer_ducking", "headphone_virtualizer_steerer_source_distance",
        )

        /** Columnas de la tabla profiles (por perfil, comunes a todas las salidas). */
        val PROF_KEYS = listOf(
            "headphone_virtualizer_mode", "advanced_headphone_virtualizer_lr_angle",
            "reverb_suppression_enable", "reverb_suppression_amount", "volume_modeler_enable",
            "mi_adaptive_virtualizer_steering_enable", "mi_dv_leveler_steering_enable",
            "mi_ieq_steering_enable", "mi_surround_compressor_steering_enable",
        )
        val TUNING_KEYS = listOf("bass_enhancer_boost", "bass_enhancer_cutoff_frequency")
    }

    data class State(
        val dolbyOn: Boolean,
        val activeProfile: Int,
        /** "perfil|endpoint" -> columnas */
        val pe: Map<String, Map<String, String>>,
        /** endpoint -> columnas de afinación */
        val tuning: Map<String, Map<String, String>>,
        /** perfil -> ganancias del ecualizador gráfico */
        val geq: Map<Int, List<Int>>,
        val geqFreqs: List<Int>,
        /** perfil -> columnas de la tabla profiles */
        val prof: Map<Int, Map<String, String>> = emptyMap(),
    )

    private val work = File(ctx.cacheDir, "dax.db")
    private val backup = File(ctx.filesDir, "dax_original.db")

    fun hasBackup() = backup.exists()

    private fun open(): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(
            work.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        db.disableWriteAheadLogging()
        return db
    }

    fun load(): State {
        work.delete()
        if (!Root.readFile(DB, work)) error("No se pudo leer la base de datos de Dolby")
        if (!backup.exists()) work.copyTo(backup)
        open().use { db ->
            fun cfg(k: String) = db.rawQuery(
                "select value from configuration where key=?", arrayOf(k)
            ).use { if (it.moveToFirst()) it.getString(0) else null }

            val pe = mutableMapOf<String, Map<String, String>>()
            for ((p, _) in PROFILES) for ((e, _) in ENDPOINTS) {
                db.rawQuery(
                    "select ${PE_KEYS.joinToString()} from profile_endpoints " +
                        "where profile_id=? and endpoint=? and custom_name='custom'",
                    arrayOf(p.toString(), e)
                ).use { c ->
                    if (c.moveToFirst()) pe["$p|$e"] =
                        PE_KEYS.mapIndexed { i, k -> k to (c.getString(i) ?: "0") }.toMap()
                }
            }

            val tuning = mutableMapOf<String, Map<String, String>>()
            for ((e, _) in ENDPOINTS) {
                db.rawQuery(
                    "select ${TUNING_KEYS.joinToString()} from tunings where endpoint=? limit 1",
                    arrayOf(e)
                ).use { c ->
                    if (c.moveToFirst()) tuning[e] =
                        TUNING_KEYS.mapIndexed { i, k -> k to (c.getString(i) ?: "0") }.toMap()
                }
            }

            val geq = mutableMapOf<Int, List<Int>>()
            var freqs = emptyList<Int>()
            for ((p, _) in PROFILES) {
                val preset = geqPreset(db, p) ?: continue
                db.rawQuery(
                    "select geq_frequency, geq_gain from geq_presets where profile_id=? and custom_name='custom'",
                    arrayOf(preset)
                ).use { c ->
                    if (c.moveToFirst()) {
                        freqs = c.getString(0).split(",").map { it.trim().toInt() }
                        geq[p] = c.getString(1).split(",").map { it.trim().toInt() }
                    }
                }
            }

            val prof = mutableMapOf<Int, Map<String, String>>()
            for ((p, _) in PROFILES) {
                db.rawQuery(
                    "select ${PROF_KEYS.joinToString()} from profiles where profile_id=? and custom_name='custom'",
                    arrayOf(p.toString())
                ).use { c ->
                    if (c.moveToFirst()) prof[p] = PROF_KEYS.mapIndexed { i, k -> k to (c.getString(i) ?: "0") }.toMap()
                }
            }

            return State(
                dolbyOn = cfg("State") == "true",
                activeProfile = cfg("Profile")?.toIntOrNull() ?: 0,
                pe = pe, tuning = tuning, geq = geq, geqFreqs = freqs, prof = prof,
            )
        }
    }

    private fun geqPreset(db: SQLiteDatabase, profile: Int): String? = db.rawQuery(
        "select selected_geq_preset_id from profiles where profile_id=? and custom_name='custom'",
        arrayOf(profile.toString())
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Guarda en la copia (filas 'default' y 'custom') y la instala. */
    fun save(s: State, restart: Boolean = true): Root.Result {
        open().use { db ->
            db.beginTransaction()
            try {
                db.execSQL("update configuration set value=? where key='State'", arrayOf(s.dolbyOn.toString()))
                db.execSQL("update configuration set value=? where key='Profile'", arrayOf(s.activeProfile.toString()))
                for ((key, cols) in s.pe) {
                    val (p, e) = key.split("|")
                    val cv = ContentValues().apply { cols.forEach { (k, v) -> put(k, v) } }
                    db.update("profile_endpoints", cv, "profile_id=? and endpoint=?", arrayOf(p, e))
                }
                for ((e, cols) in s.tuning) {
                    val cv = ContentValues().apply { cols.forEach { (k, v) -> put(k, v) } }
                    db.update("tunings", cv, "endpoint=?", arrayOf(e))
                }
                for ((p, cols) in s.prof) {
                    val cv = ContentValues().apply { cols.forEach { (k, v) -> put(k, v) } }
                    db.update("profiles", cv, "profile_id=?", arrayOf(p.toString()))
                }
                for ((p, gains) in s.geq) {
                    val preset = geqPreset(db, p) ?: continue
                    val cv = ContentValues().apply { put("geq_gain", gains.joinToString(",")) }
                    db.update("geq_presets", cv, "profile_id=?", arrayOf(preset))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return install(work, restart)
    }

    /** Reinicia Dolby para que recargue la base de datos (también reinicia el servidor de audio). */
    fun restartAudio(): Root.Result = Root.run(
        "setprop ctl.restart dms-hal", "sleep 1", "killall audioserver", "echo OK"
    )

    fun restoreOriginal(): Root.Result {
        if (!backup.exists()) return Root.Result(1, "No hay copia original")
        return install(backup)
    }

    private fun install(f: File, restart: Boolean = true): Root.Result {
        File(f.path + "-journal").delete()
        if (!Root.writeFile(f, "$DB.new")) return Root.Result(1, "No se pudo copiar la base de datos")
        // Sin reinicio: ya se aplicó en vivo, solo se guarda para que dure tras reiniciar el teléfono
        if (!restart) return Root.run(
            "mv -f $DB.new $DB",
            "chown media:audio $DB",
            "chmod 600 $DB",
            "chcon u:object_r:vendor_data_file:s0 $DB",
            "echo OK",
        )
        return Root.run(
            "setprop ctl.stop dms-hal",
            "sleep 1",
            "mv -f $DB.new $DB",
            "rm -f $DB-journal $DB-wal $DB-shm",
            "chown media:audio $DB",
            "chmod 600 $DB",
            "chcon u:object_r:vendor_data_file:s0 $DB",
            "setprop ctl.start dms-hal",
            "sleep 1",
            "killall audioserver",
            "echo OK",
        )
    }
}

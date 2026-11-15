package com.kevxngg.audiomax

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Reads and writes the configuration of the native Dolby (DAP) on the Redmi Note 14 Pro 4G.
 * The dms-hal service stores everything in /data/vendor/dolby/dax_sqlite3.db; a copy is edited
 * and then replaces the original, restarting dms-hal and audioserver.
 * Dolby gains are in 1/16 dB.
 */
class DolbyStore(private val ctx: Context) {

    companion object {
        const val DB = "/data/vendor/dolby/dax_sqlite3.db"

        /** Profile id -> display name. */
        val PROFILES: List<Pair<Int, Int>> = listOf(
            0 to R.string.profile_dynamic, 1 to R.string.profile_movie,
            2 to R.string.profile_music, 3 to R.string.profile_voice,
            4 to R.string.profile_mobility, 5 to R.string.profile_on_the_go,
            6 to R.string.profile_commute, 7 to R.string.profile_travel,
        )
        /** Endpoint id -> display name. */
        val ENDPOINTS: List<Pair<String, Int>> = listOf(
            "speaker" to R.string.endpoint_speaker, "headphone" to R.string.endpoint_headphone_long,
        )
        /** Intelligent equalizer preset id -> display name. */
        val IEQ_PRESETS: List<Pair<Int, Int>> = listOf(
            1 to R.string.ieq_detailed, 2 to R.string.ieq_balanced, 3 to R.string.ieq_warm,
        )

        /** profile_endpoints columns handled by the app. */
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

        /** Columns of the profiles table (per profile, shared by all outputs). */
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
        /** "profile|endpoint" -> columns */
        val pe: Map<String, Map<String, String>>,
        /** endpoint -> tuning columns */
        val tuning: Map<String, Map<String, String>>,
        /** profile -> graphic equalizer gains */
        val geq: Map<Int, List<Int>>,
        val geqFreqs: List<Int>,
        /** profile -> columns of the profiles table */
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
        if (!Root.readFile(DB, work)) error(ctx.getString(R.string.err_read_db))
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

    /** Saves to the copy ('default' and 'custom' rows) and installs it. */
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

    /** Restarts Dolby so it reloads the database (also restarts the audio server). */
    fun restartAudio(): Root.Result = Root.run(
        "setprop ctl.restart dms-hal", "sleep 1", "killall audioserver", "echo OK"
    )

    fun restoreOriginal(): Root.Result {
        if (!backup.exists()) return Root.Result(1, "No original backup")
        return install(backup)
    }

    private fun install(f: File, restart: Boolean = true): Root.Result {
        File(f.path + "-journal").delete()
        if (!Root.writeFile(f, "$DB.new")) return Root.Result(1, "Could not copy the database")
        // No restart: already applied live, only saved so it survives a phone reboot
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

package com.kevxngg.audiomax

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class UpdateInfo(
    val build: Int,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val minBuild: Int = 0,
    val version: String = "",
    val sha256: String = "",
) {
    val label: String get() = version.ifEmpty { "build $build" }
}

/** Busca nuevas versiones en las Releases de GitHub y las instala con root. */
object Updater {
    private const val REPO = "kevxngg/dolby-atmos-obsidian"
    private const val FEED = "https://github.com/$REPO/releases/latest/download/update.json"
    private const val TMP = "/data/local/tmp/dolbyatmos-update.apk"

    @Volatile private var snoozedBuild = 0
    @Volatile private var snoozedUntil = 0L

    fun isSnoozed(build: Int) = build == snoozedBuild && System.currentTimeMillis() < snoozedUntil
    fun snooze(build: Int) {
        snoozedBuild = build
        snoozedUntil = System.currentTimeMillis() + 60 * 60 * 1000
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun skippedBuild(ctx: Context) = prefs(ctx).getInt("skipped", 0)
    fun skip(ctx: Context, build: Int) = prefs(ctx).edit().putInt("skipped", build).apply()

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    fun currentBuild(ctx: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    } catch (e: Exception) { 0 }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "DolbyAtmos-Android")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** null si no se pudo consultar. */
    fun fetch(ctx: Context): UpdateInfo? = try {
        val j = JSONObject(get("$FEED?t=${System.currentTimeMillis()}"))
        val info = UpdateInfo(
            j.getInt("build"), j.optString("notes"), j.getString("apk"), j.optLong("size"),
            j.optInt("min_build", 0), j.optString("version"), j.optString("sha256"),
        )
        val url = URL(info.apkUrl)
        require(url.protocol == "https" && url.host == "github.com" &&
            url.path.startsWith("/$REPO/releases/download/") && url.path.endsWith(".apk"))
        info
    } catch (e: Exception) {
        Log.w("AudioMaxUpdater", "Update check failed: ${e.message}")
        null
    }

    fun isRequired(ctx: Context, info: UpdateInfo) = UpdatePolicy.isRequired(currentBuild(ctx), info.minBuild)

    fun isOffered(ctx: Context, info: UpdateInfo, ignoreSkipped: Boolean = false) =
        UpdatePolicy.isOffered(currentBuild(ctx), info.build, skippedBuild(ctx), ignoreSkipped, info.minBuild)

    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Instala con root y vuelve a abrir la app (Android cierra la versión abierta al instalar). */
    fun install(ctx: Context, file: File, sha256: String?, size: Long?): Boolean {
        if (!verify(ctx, file, sha256, size)) {
            file.delete()
            return false
        }
        if (!Root.writeFile(file, TMP)) return false
        file.delete()
        Root.run(
            "chmod 644 $TMP",
            "setsid sh -c 'pm install -r $TMP && am start -n ${ctx.packageName}/${MainActivity::class.java.name}; rm -f $TMP' " +
                "</dev/null >/dev/null 2>&1 &"
        )
        return true
    }

    /** Tamaño y SHA-256 publicados, mismo paquete, misma firma y versión más nueva. */
    private fun verify(ctx: Context, file: File, sha256: String?, size: Long?): Boolean = try {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        when {
            !file.exists() || file.length() == 0L -> false
            size != null && size > 0 && file.length() != size -> false
            !sha256.isNullOrBlank() && !sha256Of(file).equals(sha256, ignoreCase = true) -> false
            archive == null || archive.packageName != ctx.packageName -> false
            PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed) -> false
            else -> signers(archive).intersect(signers(installed)).isNotEmpty()
        }
    } catch (e: Exception) { false }

    private fun signers(pi: PackageInfo): Set<String> {
        val si = pi.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.US, "%02x", it) }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun openInBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, required: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val current = remember { Updater.currentVersion(ctx) }

    fun start() {
        busy = true
        status = "Descargando…"
        progress = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val f = Updater.download(ctx, info.apkUrl) { p -> progress = p }
                    status = "Instalando… la app se cerrará y volverá a abrirse."
                    Updater.install(ctx, f, info.sha256, info.sizeBytes)
                } catch (e: Exception) { false }
            }
            if (!ok) {
                busy = false
                progress = null
                status = "No se pudo instalar automáticamente. Puedes descargarla desde el navegador."
            } else {
                delay(30_000)
                busy = false
                progress = null
                status = "La instalación no se completó. Reintenta o descarga desde el navegador."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy && !required) onDismiss() },
        title = { Text(if (required) "Actualización obligatoria" else "Hay una nueva versión", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Versión ${info.label} disponible (tienes la $current)" +
                        if (info.sizeBytes > 0) String.format(Locale.US, " · %.1f MB", info.sizeBytes / 1048576f) else "",
                    fontSize = 13.sp, color = Color.Gray,
                )
                if (info.notes.isNotBlank()) ReleaseNotes(info.notes)
                progress?.let { p -> LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                status?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { start() }) { Text("Actualizar ahora") }
        },
        dismissButton = {
            Row {
                if (!busy && status != null) {
                    TextButton(onClick = { Updater.openInBrowser(ctx, info.apkUrl) }) { Text("Navegador") }
                }
                if (!required) TextButton(enabled = !busy, onClick = {
                    Updater.skip(ctx, info.build); onDismiss()
                }) { Text("Omitir") }
                if (!required) TextButton(enabled = !busy, onClick = {
                    Updater.snooze(info.build); onDismiss()
                }) { Text("Más tarde") }
            }
        },
    )
}

/** "## Título" en negrita y "- punto" como viñetas. */
@Composable
fun ReleaseNotes(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.trim().lines().forEach { raw ->
            val t = raw.trim()
            when {
                t.startsWith("#") -> Text(
                    t.trimStart('#').trim(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                t.startsWith("- ") || t.startsWith("* ") -> Row {
                    Text("•", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(14.dp))
                    Text(t.drop(2), fontSize = 13.sp)
                }
                t.isNotEmpty() -> Text(t, fontSize = 13.sp)
            }
        }
    }
}

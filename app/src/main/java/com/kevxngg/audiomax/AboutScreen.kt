package com.kevxngg.audiomax

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        PrefGroup {
            Column(Modifier.padding(20.dp)) {
                Text("Dolby Atmos · Obsidian", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Versión ${Updater.currentVersion(ctx)} · Redmi Note 14 Pro 4G",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Controla el Dolby Atmos que ya trae el teléfono: no instala otro efecto. Necesita root.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        PrefGroup("Código") {
            NavPref("Código fuente y versiones", "github.com/kevxngg/dolby-atmos-obsidian") {
                Updater.openInBrowser(ctx, "https://github.com/kevxngg/dolby-atmos-obsidian")
            }
        }
        PrefGroup("Créditos") {
            NavPref("Paranoid Android y Lunaris AOSP", "Control del efecto Dolby · Apache-2.0") {
                Updater.openInBrowser(ctx, "https://www.apache.org/licenses/LICENSE-2.0")
            }
            PrefDivider()
            NavPref("AutoEq", "Curvas de corrección de auriculares · Jaakko Pasanen") {
                Updater.openInBrowser(ctx, "https://github.com/jaakkopasanen/AutoEq")
            }
        }
        FooterText("Dolby, Dolby Atmos y el símbolo de la doble D son marcas de Dolby Laboratories. Esta app no está afiliada a Dolby.")
        Spacer(Modifier.height(24.dp))
    }
}

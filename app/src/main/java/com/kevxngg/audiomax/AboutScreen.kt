package com.kevxngg.audiomax

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        PrefGroup {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.about_version, Updater.currentVersion(ctx)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        PrefGroup(stringResource(R.string.about_code)) {
            NavPref(stringResource(R.string.about_source), "github.com/kevxngg/dolby-atmos-obsidian") {
                Updater.openInBrowser(ctx, "https://github.com/kevxngg/dolby-atmos-obsidian")
            }
        }
        PrefGroup(stringResource(R.string.about_credits)) {
            NavPref(stringResource(R.string.about_pa), stringResource(R.string.about_pa_summary)) {
                Updater.openInBrowser(ctx, "https://www.apache.org/licenses/LICENSE-2.0")
            }
            PrefDivider()
            NavPref(stringResource(R.string.about_autoeq), stringResource(R.string.about_autoeq_summary)) {
                Updater.openInBrowser(ctx, "https://github.com/jaakkopasanen/AutoEq")
            }
        }
        FooterText(stringResource(R.string.about_trademark))
        Spacer(Modifier.height(24.dp))
    }
}

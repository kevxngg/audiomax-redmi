package com.kevxngg.audiomax

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Material You with the wallpaper colors, like Android Settings. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Large main switch (AOSP MainSwitchBar). */
@Composable
fun MainSwitchBar(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (checked) cs.primaryContainer else cs.surfaceContainerHighest
    val fg = if (checked) cs.onPrimaryContainer else cs.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .clickable { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, color = fg, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Preference group with rounded corners (Android 16 style). */
@Composable
fun PrefGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(
            title,
            Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            content = content,
        )
    }
}

@Composable
fun PrefDivider() = HorizontalDivider(
    Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.surface, thickness = 2.dp
)

@Composable
private fun PrefText(title: String, summary: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    val a = if (enabled) 1f else 0.38f
    Column(modifier) {
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = a))
        if (summary != null) Text(
            summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a)
        )
    }
}

@Composable
fun SwitchPref(
    title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrefText(title, summary, enabled, Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Slider; reports the final value on release (so it is applied only once). */
@Composable
fun SliderPref(
    title: String, value: Int, min: Int, max: Int, step: Int, enabled: Boolean = true,
    fmt: (Int) -> String, onChange: (Int) -> Unit, onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrefText(title, null, enabled, Modifier.weight(1f))
            Text(fmt(value), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange((it / step).roundToInt() * step) },
            onValueChangeFinished = onDone,
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
        )
    }
}

/** List preference: shows the selected option and opens a dialog with radio buttons. */
@Composable
fun <T> ListPref(
    title: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clickable(enabled) { open = true }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        PrefText(title, options.firstOrNull { it.first == selected }?.second, enabled)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, name) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(id == selected, role = Role.RadioButton) { onSelect(id); open = false }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = null)
                        Text(name, Modifier.padding(start = 16.dp), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Resolves (id, @StringRes name) options into (id, text) pairs for [ListPref]. */
@Composable
fun <T> labels(options: List<Pair<T, Int>>): List<Pair<T, String>> =
    options.map { (id, res) -> id to stringResource(res) }

@Composable
fun NavPref(title: String, summary: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrefText(title, summary, enabled, Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FooterText(text: String) = Text(
    text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

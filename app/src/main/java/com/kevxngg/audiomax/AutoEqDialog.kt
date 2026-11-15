package com.kevxngg.audiomax

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Busca tus auriculares en AutoEq y aplica su curva de corrección al ecualizador. */
@Composable
fun AutoEqDialog(freqs: List<Int>, onApply: (List<Int>, String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<AutoEq.Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { AutoEq.index(ctx) } }
        r.onSuccess { all = it }.onFailure { error = "No se pudo descargar la lista: ${it.message}" }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("AutoEQ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Corrige la respuesta de tus auriculares con las mediciones del proyecto AutoEq.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    query, { query = it }, singleLine = true, label = { Text("Modelo (ej. Galaxy Buds, AirPods)") },
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    all == null || busy -> Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() }
                    else -> {
                        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val list = if (words.isEmpty()) emptyList()
                        else all!!.filter { e -> words.all { e.name.lowercase().contains(it) } }.take(60)
                        if (words.isNotEmpty() && list.isEmpty()) Text("Sin resultados", fontSize = 13.sp)
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(list) { e ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        busy = true
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { runCatching { AutoEq.curve(e, freqs) } }
                                            busy = false
                                            r.onSuccess { onApply(it, e.name) }
                                                .onFailure { error = "No se pudo cargar la curva: ${it.message}" }
                                        }
                                    }.padding(vertical = 10.dp)
                                ) {
                                    Text(e.name, fontSize = 15.sp)
                                    Text(e.source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cerrar") } },
    )
}

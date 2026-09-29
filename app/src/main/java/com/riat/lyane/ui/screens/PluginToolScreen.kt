package com.riat.lyane.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.riat.lyane.ui.components.SectionCard

/**
 * Eklenti aracı ekranı: eklentinin manifestinde bildirdiği bildirimsel
 * arayüzü (textfield/button/slider/switch) render eder ve eylem fonksiyonunu
 * çağırır. Böylece eklentiler kod yazmadan kendi araçlarını ekleyebilir.
 */
@Composable
fun PluginToolScreen(nav: NavController, pluginId: String, toolId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as com.riat.lyane.LyaneApp).container

    val plugin = container.plugins.plugins.value.firstOrNull { it.manifest.id == pluginId }
    val tool = plugin?.manifest?.tools?.firstOrNull { it.id == toolId }

    if (plugin == null || tool == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Araç bulunamadı", style = MaterialTheme.typography.titleMedium)
            Text("Eklenti kaldırılmış ya da devre dışı olabilir.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    var state by remember(tool.id) {
        mutableStateOf(
            tool.ui.filter { it.type != "button" }
                .associate { it.id to (it.initial.ifBlank { if (it.type == "slider") "${it.min}" else "" }) }
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(tool.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (tool.description.isNotBlank()) {
            Text(tool.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }

        SectionCard {
            tool.ui.forEach { el ->

                when (el.type) {
                    "textfield", "textarea" -> {
                        OutlinedTextField(
                            value = state[el.id] ?: "",
                            onValueChange = { state = state + (el.id to it) },
                            label = { Text(el.label) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (el.multiline || el.type == "textarea") 3 else 1,
                            maxLines = if (el.multiline || el.type == "textarea") 8 else 1
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    "slider" -> {
                        var v by remember(el.id) { mutableFloatStateOf((state[el.id] ?: "${el.min}").toFloatOrNull() ?: el.min) }
                        Text("${el.label}: ${"%.0f".format(v)}")
                        Slider(
                            value = v,
                            onValueChange = {
                                v = it
                                state = state + (el.id to "%.0f".format(it))
                            },
                            valueRange = el.min..el.max
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    "switch" -> {
                        var v by remember(el.id) { mutableStateOf(state[el.id] == "true") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(el.label, Modifier.weight(1f))
                            Switch(checked = v, onCheckedChange = {
                                v = it
                                state = state + (el.id to it.toString())
                            })
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    "text" -> {
                        Text(state[el.id] ?: el.label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    "button" -> {
                        Button(
                            onClick = {
                                busy = true; error = ""
                                Thread {
                                    val updates = container.plugins.runToolAction(
                                        pluginId, toolId, el.action, state
                                    )
                                    state = if (updates != null) state + updates else state
                                    busy = false
                                }.start()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(el.label.ifBlank { "Çalıştır" }) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Eklenti: ${plugin.manifest.name} · tamamen cihazında çalışır",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

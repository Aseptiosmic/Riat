package com.riat.lyane.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.riat.lyane.core.Device
import com.riat.lyane.core.Fmt
import com.riat.lyane.core.LyLog
import com.riat.lyane.ui.Routes
import com.riat.lyane.ui.SettingsViewModel
import com.riat.lyane.ui.components.SectionCard

/** Ayarlar: motor, indirme, gizlilik, depolama, tanılama. */
@Composable
fun SettingsScreen(nav: NavController) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    var name by remember { mutableStateOf("") }
    LaunchedEffect(settings.deviceName) { if (name.isBlank()) name = settings.deviceName }
    var storage by remember { mutableStateOf(Triple(0L, 0L, 0L)) }
    LaunchedEffect(Unit) { storage = vm.storageStats() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Ayarlar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        item {
            SectionCard(title = "Görünüm") {
                Text("Tema", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Sistem", "light" to "Açık", "dark" to "Koyu").forEach { (k, l) ->
                        FilterChip(selected = settings.theme == k, onClick = { vm.setTheme(k) }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Cihaz adı (Lyane Drop'ta görünür)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(onClick = { vm.setDeviceName(name) }) { Text("Kaydet") }
            }
        }

        item {
            SectionCard(title = "Motor") {
                var tts by remember(settings.ttsThreads) { mutableFloatStateOf(settings.ttsThreads.toFloat()) }
                var asr by remember(settings.asrThreads) { mutableFloatStateOf(settings.asrThreads.toFloat()) }
                Text("TTS iplik sayısı: ${tts.toInt()} (önerilen ${Device.ttsThreads()})", style = MaterialTheme.typography.bodySmall)
                Slider(value = tts, onValueChange = { tts = it }, valueRange = 1f..8f, steps = 6,
                    onValueChangeFinished = { vm.setTtsThreads(tts.toInt()) })
                Text("STT iplik sayısı: ${asr.toInt()} (önerilen ${Device.asrThreads()})", style = MaterialTheme.typography.bodySmall)
                Slider(value = asr, onValueChange = { asr = it }, valueRange = 1f..8f, steps = 6,
                    onValueChangeFinished = { vm.setAsrThreads(asr.toInt()) })
                Text(
                    "Model değiştirildiğinde yeni değer uygulanır. Düşük donanımda azaltın.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Sıcak kelimeler etkin", settings.hotwordsEnabled) { vm.setHotwords(it) }
            }
        }

        item {
            SectionCard(title = "Ağ ve gizlilik") {
                SwitchRow("Model indirmeleri yalnız Wi-Fi", settings.wifiOnlyDownloads) { vm.setWifiOnly(it) }
                Spacer(Modifier.height(4.dp))
                SwitchRow("Çalışırken ekranı açık tut", settings.keepScreenOn) { vm.setKeepScreenOn(it) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lyane hiçbir analitik/telemetri göndermez. İnternet yalnızca model dosyalarının " +
                        "açık kaynak dağıtım noktalarından (GitHub Releases/HuggingFace) indirilmesi için kullanılır. " +
                        "Ses tanıma ve üretimi %100 cihaz üzerindedir.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            SectionCard(title = "Depolama") {
                Text("Modeller: ${Fmt.bytes(storage.first)}", style = MaterialTheme.typography.bodySmall)
                Text("Kütüphane: ${Fmt.bytes(storage.second)}", style = MaterialTheme.typography.bodySmall)
                Text("Boş alan: ${Fmt.bytes(storage.third)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    storage = vm.storageStats()
                }) { Text("Yenile") }
            }
        }

        item {
            SectionCard(title = "Tanılama günlüğü") {
                val entries by LyLog.entries.collectAsState()
                Text(
                    "Son ${entries.size} olay (yalnızca bellekte tutulur, hiçbir yere gönderilmez)",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(6.dp))
                entries.takeLast(14).reversed().forEach {
                    Text(
                        "[${it.tag}] ${it.message.take(110)}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
        }

        item {
            SectionCard(title = "Cihaz profili") {
                Text(Device.summary(), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { nav.navigate(Routes.ABOUT) }) { Text("Hakkında ve lisanslar") }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

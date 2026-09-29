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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riat.lyane.core.Fmt
import com.riat.lyane.ui.components.SectionCard

/**
 * Lyane Drop: aynı Wi-Fi'daki iki cihaz arasında uygulama içi aktarım.
 * Sunucu (gönderen) ve istemci (alan) sekmeleri.
 */
@Composable
fun DropScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drop = (context.applicationContext as com.riat.lyane.LyaneApp).container.drop

    val hostState by drop.hostState.collectAsState()
    val clientState by drop.clientState.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Lyane Drop", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Aynı Wi-Fi ağına bağlı iki Lyane arasında veri aktarımı. " +
                "Başka hiçbir yolla veri taşınmaz: bulut yok, sunucu yok.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }) { Text("Paylaş (Sunucu)", Modifier.padding(12.dp)) }
            Tab(tab == 1, onClick = { tab = 1 }) { Text("Al (İstemci)", Modifier.padding(12.dp)) }
        }

        if (tab == 0) {
            HostTab(drop, hostState)
        } else {
            ClientTab(drop, clientState)
        }
    }
}

@Composable
private fun HostTab(
    drop: com.riat.lyane.drop.DropManager,
    hostState: com.riat.lyane.drop.DropManager.HostState
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(title = if (hostState.running) "Sunucu açık" else "Sunucu kapalı") {
                if (hostState.running) {
                    Text(
                        "Diğer cihazda: Lyane Drop → Al → bu cihazı seç ve şu PIN'i gir:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        hostState.pin.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (hostState.error.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(hostState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (hostState.log.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        hostState.log.take(6).forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { drop.stopHosting() }) { Text("Sunucuyu kapat") }
                } else {
                    if (hostState.error.isNotBlank()) {
                        Text(hostState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Paylaşmak istediğin modelleri, eklentileri, sesleri ve katalogları sunucu açarak sun. " +
                            "Karşı cihaz PIN ile eşleşir ve seçtiklerini indirir. " +
                            "Sende de 'Al' sekmesi açıkken karşıdan gelen her şey otomatik kurulur.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { drop.startHosting() }) { Text("Sunucuyu aç") }
                }
            }
        }
        item {
            SectionCard(title = "Neler paylaşılabilir?") {
                Text(
                    "• Kurulu tüm modeller (lyane.json ile birlikte — karşı tarafta ek kurulumsuz çalışır)\n" +
                        "• Eklentiler (.lyplugin)\n" +
                        "• Kütüphanedeki sesler ve transkriptler\n" +
                        "• Özel model katalogları",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ClientTab(
    drop: com.riat.lyane.drop.DropManager,
    state: com.riat.lyane.drop.DropManager.ClientState
) {
    var pin by remember { mutableStateOf("") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.phase) {
            com.riat.lyane.drop.DropManager.ClientPhase.IDLE -> {
                item {
                    SectionCard {
                        Text(
                            "Aynı Wi-Fi ağındaki Lyane'ler aranıyor…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { drop.startDiscovering() }) { Text("Cihazları ara") }
                    }
                }
            }

            com.riat.lyane.drop.DropManager.ClientPhase.DISCOVERING -> {
                item {
                    SectionCard(title = "Bulunan cihazlar") {
                        if (state.peers.isEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Henüz cihaz yok. Karşı tarafta 'Sunucuyu aç' demeyi unutmayın.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state.error.isNotBlank()) {
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(state.peers, key = { it.host + it.port }) { peer ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.titleSmall)
                                Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { drop.beginPair(peer) }) { Text("Bağlan") }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { drop.stopDiscovering(); drop.resetClient() }) { Text("Aramayı durdur") }
                }
            }

            com.riat.lyane.drop.DropManager.ClientPhase.PIN -> {
                item {
                    SectionCard(title = "PIN girin — ${state.targetName}") {
                        Text(
                            "Sunucu cihazdaki ekranda gösterilen 6 haneli kodu girin.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                            label = { Text("PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        if (state.error.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { drop.submitPin(pin) }, enabled = pin.length == 6) { Text("Eşleş") }
                            OutlinedButton(onClick = { drop.resetClient() }) { Text("Vazgeç") }
                        }
                    }
                }
            }

            com.riat.lyane.drop.DropManager.ClientPhase.BROWSING -> {
                item {
                    SectionCard(title = "${state.targetName} üzerindekiler") {
                        if (state.message.isNotBlank()) {
                            Text("✓ ${state.message}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.error.isNotBlank()) {
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { drop.refreshList() }) { Text("Yenile") }
                            OutlinedButton(onClick = { drop.resetClient() }) { Text("Bağlantıyı kes") }
                        }
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${kindLabel(item.kind)} · ${Fmt.bytes(item.sizeBytes)} ${item.detail}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = { drop.downloadItem(item.id) }) { Text("Al") }
                        }
                    }
                }
            }

            com.riat.lyane.drop.DropManager.ClientPhase.TRANSFERRING -> {
                item {
                    SectionCard(title = "Aktarılıyor…") {
                        val t = state.transfer
                        if (t != null) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            if (t.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (t.received.toFloat() / t.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Text(
                                "${Fmt.bytes(t.received)} / ${if (t.total > 0) Fmt.bytes(t.total) else "?"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "model" -> "Model"
    "plugin" -> "Eklenti"
    "audio" -> "Ses"
    "transcript" -> "Transkript"
    "catalog" -> "Katalog"
    else -> kind
}

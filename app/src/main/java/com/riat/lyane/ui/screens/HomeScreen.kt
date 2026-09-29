package com.riat.lyane.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.riat.lyane.core.Fmt
import com.riat.lyane.ui.HomeViewModel
import com.riat.lyane.ui.Routes
import com.riat.lyane.ui.components.LocalBadge
import com.riat.lyane.ui.components.SectionCard
import com.riat.lyane.ui.components.StatChip

@Composable
fun HomeScreen(nav: NavController) {
    val vm: HomeViewModel = viewModel()
    val installed by vm.installed.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val libraryItems by vm.libraryItems.collectAsState()
    val plugins by vm.plugins.collectAsState()
    val settings by vm.settings.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lyane", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Yerel ses motoru · konuş ve dinle",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Icon(Icons.Filled.Settings, contentDescription = null,
                    Modifier.clickable { nav.navigate(Routes.SETTINGS) })
            }
            Spacer(Modifier.height(4.dp))
            LocalBadge()
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudOff, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Tüm işlem bu cihazda yapılır. Sesin ve metinlerin hiçbir sunucuya gönderilmez. " +
                            "İnternet yalnızca model indirmek için kullanılır.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Model", "${installed.size}", Modifier.weight(1f))
                StatChip("Kütüphane", "${libraryItems.size}", Modifier.weight(1f))
                StatChip("Eklenti", "${plugins.size}", Modifier.weight(1f))
            }
        }

        item {
            Text("Hızlı başla", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        item { QuickTile(Icons.Filled.RecordVoiceOver, "Konuş", "Metni sese çevir") { nav.navigate(Routes.SPEAK) } }
        item { QuickTile(Icons.Filled.Mic, "Dinle", "Canlı çeviriyazı") { nav.navigate(Routes.LISTEN) } }
        item { QuickTile(Icons.Filled.Download, "Modeller", "Model kataloğundan indir") { nav.navigate(Routes.MODELS) } }
        item { QuickTile(Icons.Filled.SwapHoriz, "Lyane Drop", "Aynı Wi-Fi'dan cihaza aktar") { nav.navigate(Routes.DROP) } }

        if (installed.isEmpty()) {
            item {
                SectionCard(title = "İlk adım") {
                    Text(
                        "Başlamak için bir TTS (konuşma) ve/veya STT (dinleme) modeli indirin.\n\n" +
                            "Öneriler:\n" +
                            "• Türkçe konuşma: Piper tr_TR sesleri (~21 MB)\n" +
                            "• Türkçe dinleme: Whisper Tiny (~116 MB)\n" +
                            "• En kaliteli İngilizce konuşma: Kokoro int8 (~103 MB)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        val activeDownloads = downloads.values.filterIsInstance<com.riat.lyane.model.ModelDownloadManager.State.Downloading>()
        if (activeDownloads.isNotEmpty()) {
            item {
                SectionCard(title = "İndirmeler sürüyor") {
                    activeDownloads.forEach { d ->
                        Text(
                            "${Fmt.bytes(d.downloaded)} / ${if (d.total > 0) Fmt.bytes(d.total) else "?"} · ${Fmt.speed(d.speed)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Cihaz") {
                Text(vm.deviceSummary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Depolama: modeller ${Fmt.bytes(vm.store().usedSpaceBytes())} · boş ${Fmt.bytes(vm.store().freeSpaceBytes())}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Icon(Icons.Filled.ArrowForward, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

package com.riat.lyane.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.core.Fmt
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.model.TaskType
import com.riat.lyane.ui.FileTranscribeViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard

/** Dosya çeviriyazma: ses/video dosyası → metin (tamamen cihazda). */
@Composable
fun FileTranscribeScreen() {
    val vm: FileTranscribeViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val models by vm.asrModels.collectAsState()

    val asrModels = models.filter { it.task == TaskType.ASR }
    val selectedModel = asrModels.firstOrNull { it.id == ui.modelId }
    val isWhisper = selectedModel?.engine == "whisper"
    val context = androidx.compose.ui.platform.LocalContext.current

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = uri?.let { com.riat.lyane.ui.queryDisplayName(context, it) }
        vm.pick(uri, name)
    }
    val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && ui.text.isNotBlank()) {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(ui.text.toByteArray())
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Dosya Çeviriyazı", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        if (asrModels.isEmpty()) {
            EmptyState("Dinleme modeli yok", "Önce Mağaza'dan bir STT modeli indirin (ör. Whisper Tiny)")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Dosya") {
                    Text(ui.fileName.ifBlank { "Seçili dosya yok" }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("audio/*", "video/*", "*/*")) }) {
                        Text(if (ui.uri == null) "Ses/video dosyası seç" else "Başka dosya seç")
                    }
                }
            }
            item {
                SectionCard(title = "Model") {
                    asrModels.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = ui.modelId == m.id,
                                onClick = { vm.setModel(m.id) },
                                label = { Text(m.name) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (isWhisper) {
                        Spacer(Modifier.height(4.dp))
                        Text("Dil", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "Otomatik", "tr" to "TR", "en" to "EN").forEach { (k, l) ->
                                FilterChip(selected = ui.language == k, onClick = { vm.setLanguage(k) }, label = { Text(l) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Görev", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = ui.task == "transcribe",
                                onClick = { vm.setTask("transcribe") }, label = { Text("Çeviriyaz") }
                            )
                            FilterChip(
                                selected = ui.task == "translate",
                                onClick = { vm.setTask("translate") }, label = { Text("İngilizceye çevir") }
                            )
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Ayarlar") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sessizlik algılama (VAD)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Uzun kayıtları konuşma parçalarına böler; zamana göre satırlar ve SRT üretir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = ui.useVad, onCheckedChange = { vm.setVad(it) })
                    }
                }
            }
            item {
                when (ui.phase) {
                    FileTranscribeViewModel.Phase.DECODING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    FileTranscribeViewModel.Phase.TRANSCRIBING -> {
                        Column {
                            LinearProgressIndicator(
                                progress = { if (ui.progressMax > 0) ui.progress / 100f else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Çözümleniyor… %${ui.progress}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> Unit
                }
                if (ui.error.isNotBlank()) {
                    Text("Hata: ${ui.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.run() },
                    enabled = ui.uri != null && ui.phase !in listOf(
                        FileTranscribeViewModel.Phase.DECODING,
                        FileTranscribeViewModel.Phase.TRANSCRIBING
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("✍ Çeviriyaz") }
            }
            if (ui.segments.isNotEmpty()) {
                item {
                    SectionCard(title = "Sonuç (${ui.segments.size} bölüm)") {
                        ui.segments.forEach { s ->
                            Text(
                                "[${Fmt.duration((s.startSec * 1000).toLong())}] ${s.text.trim()}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { exportTxt.launch("transkript.txt") }) { Text("TXT dışa aktar") }
                        }
                        Text(
                            "Kütüphaneye otomatik kaydedildi · SRT: Kütüphane → dışa aktar",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

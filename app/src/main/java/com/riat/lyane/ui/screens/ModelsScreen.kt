package com.riat.lyane.ui.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.core.Fmt
import com.riat.lyane.model.ModelDownloadManager
import com.riat.lyane.model.ModelSpec
import com.riat.lyane.model.TaskType
import com.riat.lyane.ui.ModelsViewModel
import com.riat.lyane.ui.components.ConfirmDialog
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.ProgressRow
import com.riat.lyane.ui.components.RatingDots
import com.riat.lyane.ui.components.SectionCard

/** Modeller: Mağaza (katalog) · Yüklü · İçe Aktar */
@Composable
fun ModelsScreen(nav: androidx.navigation.NavController) {
    val vm: ModelsViewModel = viewModel()
    val catalog by vm.catalog.collectAsState()
    val installed by vm.installed.collectAsState()
    val states by vm.downloadStates.collectAsState()
    val settings by vm.settings.collectAsState()
    val importMsg by vm.importStatus.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var taskFilter by remember { mutableStateOf("all") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // bildirim izni (indirme servisi için)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (uri.lastPathSegment?.endsWith(".json") == true) vm.importCatalog(uri) else vm.importArchive(uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }) { Text("Mağaza", Modifier.padding(14.dp)) }
            Tab(tab == 1, onClick = { tab = 1 }) { Text("Yüklü (${installed.size})", Modifier.padding(14.dp)) }
            Tab(tab == 2, onClick = { tab = 2 }) { Text("İçe Aktar", Modifier.padding(14.dp)) }
        }

        when (tab) {
            0 -> {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Model ara…") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "all" to "Tümü", TaskType.TTS to "Konuşma", TaskType.ASR to "Dinleme",
                            TaskType.ASR_LIVE to "Canlı", TaskType.VAD to "Bileşen"
                        ).forEach { (k, label) ->
                            FilterChip(selected = taskFilter == k, onClick = { taskFilter = k }, label = { Text(label) })
                        }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(Modifier.height(10.dp)) }
                    val filtered = catalog.filter { m ->
                        (taskFilter == "all" || m.task == taskFilter) &&
                            (query.isBlank() || m.name.contains(query, true) ||
                                m.languages.any { it.contains(query, true) } ||
                                m.description.contains(query, true))
                    }
                    items(filtered, key = { it.id }) { spec ->
                        ModelCard(
                            spec = spec,
                            installed = installed.any { it.id == spec.id },
                            state = states[spec.id],
                            isDefault = settings.defaultTtsModelId == spec.id || settings.defaultAsrModelId == spec.id,
                            onDownload = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                vm.download(spec)
                            },
                            onPause = { vm.pause(spec.id) },
                            onResume = { vm.resume(spec.id) },
                            onCancel = { vm.cancel(spec.id) }
                        )
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyState("Bu süzgece uyan model yok", "Farklı bir kategori ya da arama deneyin") }
                    }
                    item { Spacer(Modifier.height(30.dp)) }
                }
            }

            1 -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(Modifier.height(12.dp)) }
                    if (installed.isEmpty()) {
                        item { EmptyState("Henüz model yok", "Mağaza sekmesinden bir model indirin") }
                    }
                    items(installed, key = { it.id }) { m ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${taskLabel(m.task)} · ${m.engine} · ${m.spec.languages.joinToString(", ")} · ${Fmt.bytes(m.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { deleteTarget = m.id }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Sil")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (m.task == TaskType.TTS) {
                                    AssistChip(
                                        onClick = { vm.setDefaultTts(m.id) },
                                        label = { Text(if (settings.defaultTtsModelId == m.id) "★ Varsayılan" else "Varsayılan yap") },
                                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.width(16.dp)) }
                                    )
                                }
                                if (m.task == TaskType.ASR || m.task == TaskType.ASR_LIVE) {
                                    AssistChip(
                                        onClick = { vm.setDefaultAsr(m.id) },
                                        label = { Text(if (settings.defaultAsrModelId == m.id) "★ Varsayılan" else "Varsayılan yap") },
                                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.width(16.dp)) }
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(30.dp)) }
                }
            }

            2 -> {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(title = "Harici model içe aktar") {
                        Text(
                            "sherpa-onnx uyumlu herhangi bir model arşivini (.tar.bz2, .tar.gz, .zip) " +
                                "ya da tek dosyalık .onnx modelini seçin. Lyane motor türünü otomatik algılar.\n\n" +
                                "İpucu: HuggingFace'teki k2-fsa / csukuangfj modelleri uyumludur.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("*/*"))
                        }) { Text("Model arşivi seç") }
                    }
                    SectionCard(title = "Özel model kataloğu") {
                        Text(
                            "Kendi katalog JSON'unuzu ekleyerek mağazaya istediğiniz modeli koyabilirsiniz. " +
                                "Katalog biçimi için depodaki docs/MODELS.md dosyasına bakın. " +
                                "Kataloglar Lyane Drop ile başka cihazdan da alınabilir.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        }) { Text("Katalog JSON seç") }
                    }
                    if (importMsg.isNotEmpty()) {
                        Text(importMsg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    deleteTarget?.let { id ->
        val m = installed.firstOrNull { it.id == id }
        ConfirmDialog(
            title = "Model silinsin mi?",
            text = "${m?.name ?: id} (${Fmt.bytes(m?.sizeBytes ?: 0)}) kalıcı olarak silinecek.",
            onConfirm = { vm.delete(id); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

fun taskLabel(task: String): String = when (task) {
    TaskType.TTS -> "Konuşma (TTS)"
    TaskType.ASR -> "Dinleme (STT)"
    TaskType.ASR_LIVE -> "Canlı dinleme"
    TaskType.VAD -> "Bileşen (VAD)"
    else -> task
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    installed: Boolean,
    state: ModelDownloadManager.State?,
    isDefault: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (spec.recommended) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text("önerilen") })
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${taskLabel(spec.task)} · ${spec.languages.joinToString(", ").ifBlank { spec.engine}} · ${Fmt.bytes(spec.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Text(spec.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RatingDots("Kalite", spec.quality)
                    RatingDots("Hız", spec.speed)
                }
                Text(
                    "Lisans: ${spec.license.ifBlank { "?" }} · ${spec.author}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        when (state) {
            is ModelDownloadManager.State.Queued -> Text("Kuyrukta…", style = MaterialTheme.typography.bodySmall)
            is ModelDownloadManager.State.Downloading -> {
                ProgressRow("İndiriliyor", state.downloaded, state.total, Fmt.speed(state.speed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPause) { Icon(Icons.Filled.Pause, null) }
                    OutlinedButton(onClick = onCancel) { Text("İptal") }
                }
            }
            is ModelDownloadManager.State.Extracting -> {
                ProgressRow("Arşiv açılıyor (${state.filesDone} dosya)", 0, 0)
                OutlinedButton(onClick = onCancel) { Text("İptal") }
            }
            is ModelDownloadManager.State.Verifying -> Text("Doğrulanıyor…", style = MaterialTheme.typography.bodySmall)
            is ModelDownloadManager.State.Paused -> {
                ProgressRow("Duraklatıldı", state.downloaded, state.total)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onResume) { Icon(Icons.Filled.PlayArrow, null); Text("Devam") }
                    OutlinedButton(onClick = onCancel) { Text("Vazgeç") }
                }
            }
            is ModelDownloadManager.State.Failed -> {
                Text("Hata: ${state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onDownload) { Text("Yeniden dene") }
            }
            is ModelDownloadManager.State.Completed, null -> {
                if (installed) {
                    Text("✓ Kurulu${if (isDefault) " · varsayılan" else ""}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onDownload) { Text("İndir · ${Fmt.bytes(spec.sizeBytes)}") }
                }
            }
            is ModelDownloadManager.State.Cancelled -> Button(onClick = onDownload) { Text("İndir") }
        }
    }
}

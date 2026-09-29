package com.riat.lyane.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.riat.lyane.core.Fmt
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.ui.LibraryViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard
import com.riat.lyane.ui.Routes

/** Kütüphane: üretilen sesler ve transkriptler (hepsi yerel). */
@Composable
fun LibraryScreen(nav: NavController) {
    val vm: LibraryViewModel = viewModel()
    val items by vm.items.collectAsState()
    val playingId by vm.playingId.collectAsState()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) {
            val target = pendingExport
            val f = target?.let { vm.fileOf(it) }
            if (f != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
            }
            pendingExport = null
        }
    }
    val exportSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            val t = pendingExport
            val srt = buildString {
                t?.segments?.forEachIndexed { i, s ->
                    append("${i + 1}\n")
                    append("${srtTime(s.startSec)} --> ${srtTime(s.endSec)}\n")
                    append(s.text.trim()).append("\n\n")
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(srt.toByteArray()) }
            pendingExport = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWav(uri)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kütüphane", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${items.size} öge · hiçbiri bulutta değil",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) { Text("WAV al") }
        }
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            EmptyState("Kütüphane boş", "Konuş ekranından ses üretin ya da Dinle ile transkript oluşturun")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${if (item.kind == "audio") "🔊 ses" else "📝 transkript"} · " +
                                    "${Fmt.duration(item.durationMs)} · ${Fmt.relTime(item.createdAtMs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (item.text.isNotBlank()) {
                                Text(
                                    item.text.take(90) + if (item.text.length > 90) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 2
                                )
                            }
                        }
                        if (item.kind == "audio") {
                            IconButton(onClick = { vm.togglePlay(item) }) {
                                Icon(
                                    if (playingId == item.id) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = "Oynat"
                                )
                            }
                        }
                        IconButton(onClick = { vm.delete(item.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Sil")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.kind == "audio") {
                            OutlinedButton(onClick = {
                                pendingExport = item
                                exportLauncher.launch("${item.title}.wav")
                            }) { Text("WAV") }
                        } else {
                            OutlinedButton(onClick = {
                                pendingExport = item
                                exportSrt.launch("${item.title}.srt")
                            }) { Text("SRT") }
                        }
                        OutlinedButton(onClick = { nav.navigate(Routes.DROP) }) { Text("Drop ile gönder") }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

// dışa aktarma sırasında bekletilen öge
private var pendingExport: LibraryRepository.Item? = null

private fun srtTime(sec: Double): String {
    val ms = (sec * 1000).toLong()
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1000
    val milli = ms % 1000
    return String.format("%02d:%02d:%02d,%03d", h, m, s, milli)
}

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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.core.Fmt
import com.riat.lyane.library.LibraryRepository
import com.riat.lyane.ui.HomeViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard

/** Kıyaslama ekranı: model yükleme + RTF ölçümü. */
@Composable
fun BenchmarkScreen() {
    val vm: HomeViewModel = viewModel()
    val installed by vm.installed.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as com.riat.lyane.LyaneApp).container

    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<com.riat.lyane.engine.Benchmarker.Result?>(null) }
    var history by remember { mutableStateOf(container.benchmarker.history()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kıyaslama", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Modelleri bu cihazda ölçün: yükleme süresi ve gerçek zaman çarpanı.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        if (installed.isEmpty()) {
            EmptyState("Ölçülecek model yok", "Önce bir model indirin")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(installed, key = { it.id }) { m ->
                SectionCard {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(taskLabel(m.task) + " · " + m.engine, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Button(onClick = {
                            running = true; error = ""; lastResult = null
                            vm.launch {
                                runCatching {
                                    if (m.task == com.riat.lyane.model.TaskType.TTS) {
                                        container.benchmarker.runTts(
                                            m,
                                            "Lyane kıyaslama metni. Bu cümle, model hızını ölçmek için birkaç saniye süren bir konuşma üretmelidir."
                                        )
                                    } else {
                                        // ASR: en son kütüphane sesini kullan
                                        val audio = container.library.items.value.firstOrNull { it.kind == "audio" }
                                        val file = audio?.let { container.library.fileOf(it) }
                                        if (file != null) {
                                            val wav = com.riat.lyane.engine.WavIo.read(file)
                                            container.benchmarker.runAsr(m, wav.samples, wav.sampleRate)
                                        } else {
                                            error = "ASR ölçümü için kütüphanede bir ses gerekir (önce Konuş ekranından bir metin seslendirin)"
                                            null
                                        }
                                    }
                                }.onSuccess {
                                    lastResult = it
                                    history = container.benchmarker.history()
                                }.onFailure {
                                    error = it.message ?: "hata"
                                }
                                running = false
                            }
                        }) { Text("Ölç") }
                    }
                }
            }

            lastResult?.let { r ->
                item {
                    SectionCard(title = "Son sonuç") {
                        Text("Model: ${r.modelName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Yükleme: ${r.loadMs} ms", style = MaterialTheme.typography.bodySmall)
                        Text("İşleme: ${"%.2f".format(r.processMs)} ms", style = MaterialTheme.typography.bodySmall)
                        Text("Ses uzunluğu: ${Fmt.seconds(r.audioSec)}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Gerçek zaman çarpanı: ${"%.1f".format(r.xRealtime)}x (RTF ${"%.3f".format(r.rtf)})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (error.isNotBlank()) {
                item { Text("Hata: $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            if (history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Geçmiş", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = {
                            container.benchmarker.clear(); history = emptyList()
                        }) { Text("Temizle") }
                    }
                }
                items(history.size) { i ->
                    val r = history[i]
                    Text(
                        "${r.modelName} · ${"%.2f".format(r.xRealtime)}x · yükleme ${r.loadMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

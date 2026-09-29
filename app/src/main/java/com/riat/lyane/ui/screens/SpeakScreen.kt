package com.riat.lyane.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.core.Fmt
import com.riat.lyane.model.TaskType
import com.riat.lyane.speak.SpeakController
import com.riat.lyane.ui.SpeakViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard

/** Konuş (TTS) ekranı: metin → yerel ses. */
@Composable
fun SpeakScreen() {
    val vm: SpeakViewModel = viewModel()
    val models by vm.ttsModels.collectAsState()
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()

    val ttsModels = models.filter { it.task == TaskType.TTS }
    var text by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var sid by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var save by remember { mutableStateOf(true) }

    LaunchedEffect(ttsModels, settings.defaultTtsModelId) {
        if (modelId.isBlank() || ttsModels.none { it.id == modelId }) {
            modelId = ttsModels.firstOrNull { it.id == settings.defaultTtsModelId }?.id
                ?: ttsModels.firstOrNull()?.id ?: ""
        }
    }

    val current = ttsModels.firstOrNull { it.id == modelId }
    val busy = state.phase == SpeakController.Phase.PREPARING || state.phase == SpeakController.Phase.SPEAKING

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Konuş", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Metni tamamen bu cihazda seslendirilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        if (ttsModels.isEmpty()) {
            EmptyState("Konuşma modeli yok", "Modeller → Mağaza'dan bir TTS modeli indirin (ör. Piper tr_TR)")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Ses modeli") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ttsModels, key = { it.id }) { m ->
                            FilterChip(
                                selected = m.id == modelId,
                                onClick = { modelId = m.id; sid = 0 },
                                label = { Text(m.name) }
                            )
                        }
                    }
                }
            }

            // Konuşmacı seçimi (Kokoro/Kitten çok sesli)
            if (current != null && current.spec.speakers.isNotEmpty()) {
                item {
                    SectionCard(title = "Konuşmacı (${current.spec.speakers.size})") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(current.spec.speakers) { i, name ->
                                FilterChip(
                                    selected = sid == i,
                                    onClick = { sid = i },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Metin") {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("Seslendirilecek metni yazın…") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Merhaba! Ben Lyane. Tüm konuşmalar bu cihazda üretiliyor.",
                            "2026 yılına hoş geldiniz. Sayıları da okuyabilirim: 1.250 TL.",
                            "Bugün hava çok güzel, değil mi?"
                        ).forEach { sample ->
                            OutlinedButton(onClick = { text = sample }) {
                                Text(if (sample.length > 18) "örnek" else sample, maxLines = 1)
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Ayarlar") {
                    Text("Hız: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodySmall)
                    Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kütüphaneye kaydet", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = save, onCheckedChange = { save = it })
                    }
                }
            }

            item {
                when (state.phase) {
                    SpeakController.Phase.PREPARING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    SpeakController.Phase.SPEAKING -> {
                        Column {
                            LinearProgressIndicator(
                                progress = {
                                    if (state.sentenceCount > 0) state.sentenceIndex.toFloat() / state.sentenceCount else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Cümle ${state.sentenceIndex + 1}/${state.sentenceCount} · üretilen ses " +
                                    "${Fmt.seconds(state.generatedSec)} · ${"%.1f".format(state.xRealtime)}x gerçek zaman" +
                                    if (state.pluginApplied) " · eklenti uygulandı" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    else -> Unit
                }
                if (state.error.isNotBlank()) {
                    Text("Hata: ${state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.savedItemId.isNotBlank()) {
                    Text("✓ Kütüphaneye kaydedildi", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        state.phase == SpeakController.Phase.SPEAKING -> {
                            Button(onClick = { vm.pause() }, Modifier.weight(1f)) { Text("Duraklat") }
                            Button(onClick = { vm.stop() }, Modifier.weight(1f)) { Text("Durdur") }
                            OutlinedButton(onClick = { vm.skip() }) { Text("↷") }
                        }
                        state.phase == SpeakController.Phase.PAUSED -> {
                            Button(onClick = { vm.resume() }, Modifier.weight(1f)) { Text("Devam") }
                            Button(onClick = { vm.stop() }, Modifier.weight(1f)) { Text("Durdur") }
                        }
                        else -> Button(
                            onClick = { vm.start(text, modelId, sid, speed, save) },
                            enabled = text.isNotBlank() && modelId.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("🔊 Seslendir") }
                    }
                }
                if (state.phase == SpeakController.Phase.PAUSED) {
                    Text("Duraklatıldı — devam aynı cümle başından çalınır.", style = MaterialTheme.typography.labelSmall)
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

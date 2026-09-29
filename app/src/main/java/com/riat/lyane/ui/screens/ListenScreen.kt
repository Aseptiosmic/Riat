package com.riat.lyane.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.listen.ListenController
import com.riat.lyane.ui.ListenViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard

/** Dinle (canlı STT) ekranı: mikrofon → tamamen yerel çeviriyazı. */
@Composable
fun ListenScreen(requestMicPermission: () -> Unit) {
    val vm: ListenViewModel = viewModel()
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    val allModels by vm.asrModels.collectAsState()
    val models = allModels.filter { it.task == "asr_live" || it.task == "asr" }
    var modelId by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("auto") }

    LaunchedEffect(models, settings.defaultAsrModelId) {
        if (modelId.isBlank() || models.none { it.id == modelId }) {
            modelId = models.firstOrNull { it.id == settings.defaultAsrModelId }?.id
                ?: models.firstOrNull()?.id ?: ""
        }
    }
    LaunchedEffect(language) {
        if (language != "auto") vm.setWhisperLanguage(language, "transcribe")
    }

    val selected = models.firstOrNull { it.id == modelId }
    val isWhisper = selected?.engine == "whisper"
    val running = state.phase == ListenController.Phase.LISTENING

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Dinle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Canlı çeviriyazı · ses cihazdan çıkmaz",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        if (models.isEmpty()) {
            EmptyState("Dinleme modeli yok", "Modeller → Mağaza'dan Whisper ya da Zipformer modeli indirin")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Tanıma modeli") {
                    Column {
                        models.forEach { m ->
                            FilterChip(
                                selected = m.id == modelId,
                                onClick = { modelId = m.id },
                                label = { Text("${m.name} ${if (m.task == "asr_live") "· canlı" else "· VAD'lı"}") }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            if (isWhisper) {
                item {
                    SectionCard(title = "Whisper dili") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "auto" to "Otomatik", "tr" to "Türkçe", "en" to "İngilizce",
                                "de" to "Almanca", "ar" to "Arapça", "es" to "İspanyolca"
                            ).forEach { (k, label) ->
                                FilterChip(selected = language == k, onClick = { language = k }, label = { Text(label) })
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Transkript") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    ) {
                        LazyColumn {
                            item {
                                Text(
                                    state.finalText.ifBlank { "…" },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state.partial.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        state.partial,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // ses seviyesi göstergesi
                        val level = state.level.coerceIn(0f, 1f)
                        repeat(14) { i ->
                            val active = level * 14 > i
                            Surface(
                                modifier = Modifier.size(width = 8.dp, height = (10 + i * 2).dp).padding(1.dp),
                                shape = RoundedCornerShape(2.dp),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ) {}
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (running) {
                                if (state.speechActive) "🎙 konuşma algılanıyor" else "dinliyor…"
                            } else "",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            item {
                if (state.phase == ListenController.Phase.PREPARING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                if (state.error.isNotBlank()) {
                    Text("Hata: ${state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        running -> {
                            Button(onClick = { vm.stop() }, Modifier.weight(1f)) { Text("⏹ Durdur ve kaydet") }
                            OutlinedButton(onClick = { vm.pause() }) { Text("Duraklat") }
                        }
                        state.phase == ListenController.Phase.PAUSED -> {
                            Button(onClick = { vm.resume() }, Modifier.weight(1f)) { Text("Devam") }
                            Button(onClick = { vm.stop() }, Modifier.weight(1f)) { Text("Bitir") }
                        }
                        else -> Button(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.start(modelId) else requestMicPermission()
                            },
                            enabled = modelId.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("🎙 Dinlemeye başla") }
                    }
                    OutlinedButton(onClick = { vm.clear() }, enabled = state.finalText.isNotBlank()) { Text("Temizle") }
                }
                if (state.savedItemId.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Oturum kütüphaneye kaydedildi", style = MaterialTheme.typography.bodySmall)
                }
                if (state.segmentCount > 0) {
                    Text("${state.segmentCount} bölüm", style = MaterialTheme.typography.labelSmall)
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

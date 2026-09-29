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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riat.lyane.model.TaskType
import com.riat.lyane.ui.HomeViewModel
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard
import java.io.File

/**
 * Sıcak kelimeler: transducer tabanlı modellerde tanıma iyileştirmesi için
 * her model dizininde hotwords.txt düzenler.
 */
@Composable
fun HotwordsScreen() {
    val vm: HomeViewModel = viewModel()
    val installed by vm.installed.collectAsState()
    val settings by vm.settings.collectAsState()

    val asrModels = installed.filter { it.task == TaskType.ASR || it.task == TaskType.ASR_LIVE }
    var modelId by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(asrModels) {
        if (modelId.isBlank() || asrModels.none { it.id == modelId }) {
            modelId = asrModels.firstOrNull()?.id ?: ""
        }
    }
    LaunchedEffect(modelId) {
        val m = asrModels.firstOrNull { it.id == modelId }
        if (m != null) {
            val f = File(vm.store().dirOf(m), "hotwords.txt")
            text = if (f.isFile) f.readText() else ""
        }
        saved = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sıcak Kelimeler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Her satıra bir kelime ya da ifade yazın; tanımda bu terimler öne çıkar. " +
                "Transducer (Zipformer) modellerinde en etkili; Whisper'da kısmen desteklenir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        if (asrModels.isEmpty()) {
            EmptyState("STT modeli yok", "Önce Mağaza'dan bir dinleme modeli indirin")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    asrModels.forEach { m ->
                        FilterChip(selected = modelId == m.id, onClick = { modelId = m.id }, label = { Text(m.name) })
                    }
                }
            }
            item {
                SectionCard(title = "Kelime listesi (her satır bir terim)") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; saved = false },
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        placeholder = { Text("Lyane\nKokoro\nsherpa-onnx\n…") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val m = asrModels.firstOrNull { it.id == modelId } ?: return@Button
                            File(vm.store().dirOf(m), "hotwords.txt").writeText(text)
                            saved = true
                        }) { Text("Kaydet") }
                        if (saved) Text("✓ Kaydedildi — modeli yeniden yüklemek gerekmez", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SectionCard(title = "Not") {
                    Text(
                        "Sıcak kelimeler yalnızca \"Sıcak kelimeler\" ayarı açıkken kullanılır " +
                            "(Ayarlar → Motor). Ayarı değiştirirseniz dinlemeyi yeniden başlatın.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

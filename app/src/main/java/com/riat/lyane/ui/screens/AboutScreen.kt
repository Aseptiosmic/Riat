package com.riat.lyane.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riat.lyane.ui.components.SectionCard

/** Hakkında: sürüm, mimari özeti ve lisanslar. */
@Composable
fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Hakkında", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard(title = "Lyane 1.0.0") {
            Text(
                "Lyane, sesi merkeze koyan tamamen yerel bir uygulamadır: konuşma sentezi (TTS) " +
                    "ve konuşma tanıma (STT) tek bir açık kaynak çalışma zamanıyla, cihazınızın " +
                    "işlemcisi üzerinde çalışır.\n\n" +
                    "• Model kataloğu: istediğiniz modeli indirin, kalın ya da silin\n" +
                    "• İnternet yalnızca model indirmede kullanılır\n" +
                    "• Bulut depolama yok, telemetri yok, reklam yok\n" +
                    "• Cihazlar arası aktarım yalnızca Lyane Drop ile (aynı Wi-Fi)\n" +
                    "• Eklenti sistemi JS ve DEX eklentilerine açıktır",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard(title = "Açık kaynak bileşenler") {
            Text(
                "• sherpa-onnx (k2-fsa) — Apache License 2.0\n" +
                    "  Konuşma tanıma ve sentez çalışma zamanı (ONNX Runtime tabanlı)\n\n" +
                    "• ONNX Runtime — MIT License\n\n" +
                    "• Kokoro-82M — Apache License 2.0 (hexgrad)\n" +
                    "• Piper / VITS — MIT License (Rhasspy)\n" +
                    "• Matcha-TTS — Apache License 2.0 (k2-fsa/icefall)\n" +
                    "• OpenAI Whisper modelleri — MIT License\n" +
                    "• Moonshine — MIT License (Useful Sensors)\n" +
                    "• SenseVoice — Apache License 2.0 (FunAudioLLM)\n" +
                    "• Silero VAD — MIT License\n\n" +
                    "• Apache Commons Compress — Apache License 2.0\n" +
                    "• NanoHTTPD — BSD-3-Clause\n" +
                    "• Mozilla Rhino — MPL 2.0\n" +
                    "• Kotlin, Jetpack Compose (AndroidX) — Apache License 2.0\n\n" +
                    "Model lisansları model kataloğunda ve model dizinlerinde de bulunur.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard(title = "Gizlilik sözü") {
            Text(
                "Mikrofon sesiniz, metinleriniz, transkriptleriniz ve eklenti verileriniz " +
                    "cihazınızdan asla çıkmaz. Uygulama hiçbir sunucuya ölçüm, günlük veya " +
                    "içerik göndermez. Uçak modunda bile (model indirmeleri hariç) tüm " +
                    "özellikler çalışır.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

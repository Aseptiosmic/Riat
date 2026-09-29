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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hotword
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
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
import com.riat.lyane.ui.Routes
import com.riat.lyane.ui.components.LocalBadge
import com.riat.lyane.ui.components.SectionCard
import com.riat.lyane.ui.HomeViewModel

/** Araçlar: yerleşik araçlar + eklenti araçları. */
@Composable
fun ToolsScreen(nav: NavController) {
    val vm: HomeViewModel = viewModel()
    val installed by vm.installed.collectAsState()
    val plugins by vm.plugins.collectAsState()

    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.riat.lyane.LyaneApp).container
    val pluginTools = container.plugins.allTools()

    data class Tool(val icon: ImageVector, val title: String, val sub: String, val route: String)

    val tools = listOf(
        Tool(Icons.Filled.Description, "Dosya Çeviriyazı", "Ses/video dosyasını metne çevir", Routes.FILE),
        Tool(Icons.Filled.Speed, "Kıyaslama", "Model hızını ve gerçek zaman çarpanını ölç", Routes.BENCHMARK),
        Tool(Icons.Filled.Tune, "Sıcak Kelimeler", "Özel terimleri tanımada öne çıkar", Routes.HOTWORDS),
        Tool(Icons.Filled.LibraryMusic, "Kütüphane", "Sesler, transkriptler, dışa aktarma", Routes.LIBRARY),
        Tool(Icons.Filled.SwapHoriz, "Lyane Drop", "Aynı Wi-Fi'dan cihazdan cihaza aktarım", Routes.DROP),
        Tool(Icons.Filled.Extension, "Eklentiler", "Yeni yetenekler ekle", Routes.PLUGINS),
        Tool(Icons.AutoMirrored.Filled.MenuBook, "Hakkında", "Sürüm, lisanslar, gizlilik", Routes.ABOUT)
    )

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Araçlar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LocalBadge()
        }
        items(tools) { t ->
            SectionCard {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.navigate(t.route) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.sub, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
        if (pluginTools.isNotEmpty()) {
            item {
                Text("Eklenti araçları", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            items(pluginTools) { (plugin, tool) ->
                SectionCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tool.title, style = MaterialTheme.typography.titleMedium)
                            Text(tool.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        androidx.compose.material3.OutlinedButton(onClick = {
                            nav.navigate(Routes.pluginTool(plugin.manifest.id, tool.id))
                        }) { Text("Aç") }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Kurulu modeller") {
                if (installed.isEmpty()) {
                    Text("Henüz model yok", style = MaterialTheme.typography.bodySmall)
                } else {
                    installed.take(6).forEach {
                        Text("• ${it.name} — ${taskLabel(it.task)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

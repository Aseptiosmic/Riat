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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.riat.lyane.plugin.PluginPermissions
import com.riat.lyane.ui.PluginsViewModel
import com.riat.lyane.ui.Routes
import com.riat.lyane.ui.components.ConfirmDialog
import com.riat.lyane.ui.components.EmptyState
import com.riat.lyane.ui.components.SectionCard

/** Eklentiler: kurma, izinler, araçlar, günlükler. */
@Composable
fun PluginsScreen(nav: NavController) {
    val vm: PluginsViewModel = viewModel()
    val plugins by vm.plugins.collectAsState()
    val logs by vm.logs.collectAsState()
    val installMsg by vm.installStatus.collectAsState()

    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf("") }

    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installFromUri(uri)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Eklentiler", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Lyane'yi genişletmenin tamamen açık yolu: JS (Rhino) veya DEX eklentileri, " +
                "kendi araç arayüzünüz, TTS/STT kancaları. Tüm eklentiler çevrimdışı çalışır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { installLauncher.launch(arrayOf("*/*")) }) { Text(".lyplugin kur") }
            OutlinedButton(onClick = { vm.installSample() }) { Text("Örnek eklentiyi kur") }
        }
        if (installMsg.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(installMsg, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        if (plugins.isEmpty()) {
            EmptyState("Eklenti yok", "Örnek eklentiyi kurup nasıl çalıştığına bakın — sayıları Türkçe okuyan bir ön işlemci")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(plugins, key = { it.manifest.id }) { p ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.manifest.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "v${p.manifest.version} · ${p.manifest.author.ifBlank { "?" }} · ${if (p.manifest.type == "script") "JS" else p.manifest.type}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = p.enabled, onCheckedChange = { vm.setEnabled(p.manifest.id, it) })
                    }
                    if (p.manifest.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(p.manifest.description, style = MaterialTheme.typography.bodySmall)
                    }

                    if (expandedId == p.manifest.id) {
                        Spacer(Modifier.height(10.dp))
                        Text("Kancalar: ${p.manifest.hooks.joinToString(", ").ifBlank { "—" }}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("İzinler", style = MaterialTheme.typography.labelMedium)
                        p.manifest.permissions.forEach { perm ->
                            PermissionRow(vm, p.manifest.id, perm)
                        }
                        if (p.manifest.tools.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Araçlar", style = MaterialTheme.typography.labelMedium)
                            p.manifest.tools.forEach { tool ->
                                AssistChip(
                                    onClick = { nav.navigate(Routes.pluginTool(p.manifest.id, tool.id)) },
                                    label = { Text(tool.title) }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        val pluginLogs = logs[p.manifest.id].orEmpty()
                        if (pluginLogs.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Günlük (son ${pluginLogs.size})", style = MaterialTheme.typography.labelMedium)
                            pluginLogs.take(8).forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.uninstall(p.manifest.id) }) { Text("Kaldır") }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            expandedId = if (expandedId == p.manifest.id) "" else p.manifest.id
                        }) { Text(if (expandedId == p.manifest.id) "Kapat" else "Ayrıntılar") }
                        if (p.manifest.help.isNotBlank()) {
                            Text(p.manifest.help.take(60), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Geliştirici notu") {
                    Text(
                        "Eklenti paketi (.lyplugin) bir ZIP'tir: plugin.json + main.js (ya da plugin.dex). " +
                            "Tam API için depodaki docs/PLUGINS.md dosyasına bakın. " +
                            "Eklentiler ağ erişimi alamaz; kendi sandbox dizinleri dışına çıkamaz.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    deleteTarget?.let { id ->
        ConfirmDialog(
            title = "Eklenti kaldırılsın mı?",
            text = "Eklenti verileriyle birlikte silinecek.",
            onConfirm = { vm.uninstall(id); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun PermissionRow(vm: PluginsViewModel, pluginId: String, permission: String) {
    var granted by remember(permission) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pluginId, permission) {
        granted = vm.isGranted(pluginId, permission)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(permission, style = MaterialTheme.typography.labelMedium)
            Text(PluginPermissions.describe(permission), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = granted, onCheckedChange = {
            granted = it
            vm.grant(pluginId, permission, it)
        })
    }
}

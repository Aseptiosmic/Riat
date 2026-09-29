package com.riat.lyane.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.riat.lyane.R
import com.riat.lyane.ui.screens.AboutScreen
import com.riat.lyane.ui.screens.BenchmarkScreen
import com.riat.lyane.ui.screens.DropScreen
import com.riat.lyane.ui.screens.FileTranscribeScreen
import com.riat.lyane.ui.screens.HomeScreen
import com.riat.lyane.ui.screens.HotwordsScreen
import com.riat.lyane.ui.screens.ListenScreen
import com.riat.lyane.ui.screens.LibraryScreen
import com.riat.lyane.ui.screens.ModelsScreen
import com.riat.lyane.ui.screens.PluginToolScreen
import com.riat.lyane.ui.screens.PluginsScreen
import com.riat.lyane.ui.screens.SettingsScreen
import com.riat.lyane.ui.screens.SpeakScreen
import com.riat.lyane.ui.screens.ToolsScreen

object Routes {
    const val HOME = "home"
    const val SPEAK = "speak"
    const val LISTEN = "listen"
    const val MODELS = "models"
    const val TOOLS = "tools"
    const val FILE = "file"
    const val BENCHMARK = "benchmark"
    const val HOTWORDS = "hotwords"
    const val LIBRARY = "library"
    const val PLUGINS = "plugins"
    const val DROP = "drop"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PLUGIN_TOOL = "plugin-tool/{pluginId}/{toolId}"
    fun pluginTool(pluginId: String, toolId: String) = "plugin-tool/${pluginId}/${toolId}"
}

private data class BottomItem(val route: String, val labelRes: Int, val icon: @Composable () -> Unit)

@Composable
fun AppNav(requestMicPermission: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val bottomItems = listOf(
        BottomItem(Routes.HOME, R.string.home) { Icon(Icons.Filled.Home, contentDescription = null) },
        BottomItem(Routes.SPEAK, R.string.speak) { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null) },
        BottomItem(Routes.LISTEN, R.string.listen) { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
        BottomItem(Routes.MODELS, R.string.models) { Icon(Icons.Filled.ViewModule, contentDescription = null) },
        BottomItem(Routes.TOOLS, R.string.tools) { Icon(Icons.Filled.Extension, contentDescription = null) }
    )
    val showBottom = currentRoute in bottomItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = item.icon,
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.SPEAK) { SpeakScreen() }
            composable(Routes.LISTEN) { ListenScreen(requestMicPermission) }
            composable(Routes.MODELS) { ModelsScreen(nav) }
            composable(Routes.TOOLS) { ToolsScreen(nav) }
            composable(Routes.FILE) { FileTranscribeScreen() }
            composable(Routes.BENCHMARK) { BenchmarkScreen() }
            composable(Routes.HOTWORDS) { HotwordsScreen() }
            composable(Routes.LIBRARY) { LibraryScreen(nav) }
            composable(Routes.PLUGINS) { PluginsScreen(nav) }
            composable(Routes.DROP) { DropScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.ABOUT) { AboutScreen() }
            composable(Routes.PLUGIN_TOOL) { entry ->
                val pluginId = entry.arguments?.getString("pluginId") ?: ""
                val toolId = entry.arguments?.getString("toolId") ?: ""
                PluginToolScreen(nav, pluginId, toolId)
            }
        }
    }
}

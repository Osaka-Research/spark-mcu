package com.osakaresearch.spark

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.osakaresearch.spark.ui.ControlScreen
import com.osakaresearch.spark.ui.AiScreen
import com.osakaresearch.spark.ui.FirmwareScreen
import com.osakaresearch.spark.ui.MonitorScreen
import com.osakaresearch.spark.ui.ScanScreen

private val SparkDarkColors = darkColorScheme(
    primary = Color(0xFFFFD166),
    secondary = Color(0xFFFF7A59),
    background = Color(0xFF0E0E10),
    surface = Color(0xFF17171B),
    onPrimary = Color.Black,
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
)

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Scan : Tab("scan", "Scan", Icons.Outlined.Radar)
    data object Control : Tab("control", "Control", Icons.Outlined.Bolt)
    data object Monitor : Tab("monitor", "Monitor", Icons.Outlined.Terminal)
    data object Firmware : Tab("firmware", "Firmware", Icons.Outlined.Memory)
    data object Ai : Tab("ai", "AI", Icons.Outlined.AutoAwesome)
}

private val Tabs = listOf(Tab.Ai, Tab.Scan, Tab.Control, Tab.Monitor, Tab.Firmware)

@Composable
fun SparkApp() {
    MaterialTheme(colorScheme = SparkDarkColors) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tabs.forEach { tab ->
                        val selected = entry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (route != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Tab.Ai.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Tab.Scan.route) { ScanScreen() }
                composable(Tab.Control.route) { ControlScreen() }
                composable(Tab.Monitor.route) { MonitorScreen() }
                composable(Tab.Firmware.route) { FirmwareScreen() }
                composable(Tab.Ai.route) { AiScreen() }
            }
        }
    }
}

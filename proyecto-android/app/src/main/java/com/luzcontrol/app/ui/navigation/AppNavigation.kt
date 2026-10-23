package com.luzcontrol.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.luzcontrol.app.ui.bills.BillsScreen
import com.luzcontrol.app.ui.consumption.ConsumptionScreen
import com.luzcontrol.app.ui.home.HomeScreen
import com.luzcontrol.app.ui.prices.PricesScreen
import com.luzcontrol.app.ui.settings.SettingsScreen

enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Inicio", Icons.Filled.Home),
    Consumption("consumption", "Consumo", Icons.Filled.BarChart),
    Prices("prices", "Precios", Icons.Filled.Bolt),
    Bills("bills", "Facturas", Icons.Filled.Receipt),
    Settings("settings", "Ajustes", Icons.Filled.Settings)
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(mainViewModel: MainViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Dest.Home.route
    val syncing by mainViewModel.syncing.collectAsStateWithLifecycle()
    val title = Dest.values().firstOrNull { it.route == currentRoute }?.label ?: "LuzControl"

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        IconButton(onClick = { mainViewModel.syncNow() }, enabled = !syncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sincronizar ahora")
                        }
                    }
                )
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                Dest.values().forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { if (currentRoute != dest.route) navController.navigateTopLevel(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(navController = navController, startDestination = Dest.Home.route) {
                composable(Dest.Home.route) {
                    HomeScreen(
                        syncing = syncing,
                        onSync = mainViewModel::syncNow,
                        onOpenSimulator = { navController.navigateTopLevel(Dest.Bills.route) },
                        onOpenSettings = { navController.navigateTopLevel(Dest.Settings.route) }
                    )
                }
                composable(Dest.Consumption.route) {
                    ConsumptionScreen(onOpenSettings = { navController.navigateTopLevel(Dest.Settings.route) })
                }
                composable(Dest.Prices.route) { PricesScreen(onSync = mainViewModel::syncNow) }
                composable(Dest.Bills.route) {
                    BillsScreen(onOpenSettings = { navController.navigateTopLevel(Dest.Settings.route) })
                }
                composable(Dest.Settings.route) { SettingsScreen(mainViewModel = mainViewModel) }
            }
        }
    }
}

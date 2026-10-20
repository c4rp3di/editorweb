package com.carpe.panoptes.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.carpe.panoptes.ui.analysis.AnalysisDetailScreen
import com.carpe.panoptes.ui.analysis.AnalysisListScreen
import com.carpe.panoptes.ui.apps.AppsScreen
import com.carpe.panoptes.ui.explorer.ExplorerScreen
import com.carpe.panoptes.ui.modules.ModulesScreen
import com.carpe.panoptes.ui.settings.SettingsScreen

@Composable
fun PanoptesNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.APPS) {

        composable(Routes.APPS) {
            AppsScreen(
                onVerAnalisis = { navController.navigate(Routes.ANALYSIS_LIST) },
                onVerModulos = { navController.navigate(Routes.MODULES) },
                onAjustes = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.ANALYSIS_LIST) {
            AnalysisListScreen(
                onBack = { navController.popBackStack() },
                onAbrir = { id -> navController.navigate(Routes.analysisDetail(id)) }
            )
        }

        composable(Routes.ANALYSIS_DETAIL) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("analysisId") ?: return@composable
            AnalysisDetailScreen(
                analysisId = id,
                onBack = { navController.popBackStack() },
                onExplorar = { navController.navigate(Routes.explorer(id)) }
            )
        }

        composable(Routes.EXPLORER) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("analysisId") ?: return@composable
            ExplorerScreen(analysisId = id, onBack = { navController.popBackStack() })
        }

        composable(Routes.MODULES) {
            ModulesScreen(
                onBack = { navController.popBackStack() },
                onAjustes = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

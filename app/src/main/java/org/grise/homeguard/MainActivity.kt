package org.grise.homeguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.grise.homeguard.ui.screens.HomeScreen
import org.grise.homeguard.ui.screens.SettingsScreen
import org.grise.homeguard.ui.screens.SurveillanceScreen
import org.grise.homeguard.ui.theme.HomeGuardTheme
import org.grise.homeguard.viewmodel.SettingsViewModel
import org.grise.homeguard.viewmodel.SurveillanceViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeGuardTheme {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    AppNavHost(
                        navController = navController,
                        modifier = Modifier
                    )
                }
            }
        }
    }
}

class SettingsViewModelFactory(private val application: android.app.Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(context.applicationContext as android.app.Application)
    )
    val settings by settingsViewModel.settings.collectAsState()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            HomeScreen(
                onStartSurveillance = { navController.navigate("surveillance") },
                onOpenSettings = { navController.navigate("settings") },
                isConfigured = settings.isConfigured()
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }
        composable("surveillance") {
            val surveillanceViewModel: SurveillanceViewModel = viewModel()
            SurveillanceScreen(
                onStop = { navController.popBackStack() },
                intervalMinutes = settings.sendIntervalMinutes,
                viewModel = surveillanceViewModel
            )
        }
    }
}
package org.grise.homeguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.grise.homeguard.ui.screens.HomeScreen
import org.grise.homeguard.ui.screens.SettingsScreen
import org.grise.homeguard.ui.screens.MonitoringScreen
import org.grise.homeguard.ui.theme.HomeGuardTheme
import org.grise.homeguard.viewmodel.SettingsViewModel
import org.grise.homeguard.viewmodel.MonitoringViewModel
import org.grise.homeguard.worker.MonitoringWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeGuardTheme {
                val nav = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { _ -> AppNav(nav) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

class VMFactory(private val app: android.app.Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(app) as T
            modelClass.isAssignableFrom(MonitoringViewModel::class.java) -> MonitoringViewModel(app) as T
            else -> throw IllegalArgumentException("Unknown VM")
        }
    }
}

@Composable
fun AppNav(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val factory = remember(app) { VMFactory(app) }
    val svm: SettingsViewModel = viewModel(factory = factory)
    val mvm: MonitoringViewModel = viewModel(factory = factory)
    val s by svm.settings.collectAsState()

    val activity = ctx as? ComponentActivity
    LaunchedEffect(activity?.intent) {
        if (activity?.intent?.action == MonitoringWorker.ACTION_SHOW_MONITORING) {
            nav.navigate("monitoring")
            activity.intent.action = null
        }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartMonitoring = { nav.navigate("monitoring") },
                onOpenSettings = { nav.navigate("settings") },
                onStart = { mvm.startMonitoring() },
                isConfigured = s.isConfigured(),
                isMonitoringActive = s.isMonitoringEnabled
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, viewModel = svm)
        }
        composable("monitoring") {
            MonitoringScreen(
                onStop = { nav.popBackStack() },
                viewModel = mvm
            )
        }
    }
}

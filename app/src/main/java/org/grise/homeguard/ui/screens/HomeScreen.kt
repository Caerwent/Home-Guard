package org.grise.homeguard.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.grise.homeguard.R
import org.grise.homeguard.power.BatteryOptimization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartMonitoring: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    isConfigured: Boolean,
    isMonitoringEnabled: Boolean,
    isServiceRunning: Boolean,
) {
    val ctx = LocalContext.current
    var camPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var notifPerm by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true)
    }
    var ignoresBattery by remember { mutableStateOf(BatteryOptimization.isIgnoring(ctx)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        camPerm = perms[Manifest.permission.CAMERA] ?: camPerm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPerm = perms[Manifest.permission.POST_NOTIFICATIONS] ?: notifPerm
    }

    // The battery optimization state is changed outside of the app (settings screen), so it is
    // refreshed every time the screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoresBattery = BatteryOptimization.isIgnoring(ctx)
                camPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allPerms = camPerm && notifPerm
    val isActivelyMonitoring = isMonitoringEnabled && isServiceRunning

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.home_title)) }, actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        })
    }) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = {
                    if (isActivelyMonitoring) {
                        onStartMonitoring()
                    } else if (allPerms) {
                        onStart()
                        onStartMonitoring()
                    } else {
                        val p = mutableListOf(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
                        launcher.launch(p.toTypedArray())
                    }
                },
                enabled = isConfigured,
                modifier = Modifier.size(200.dp, 80.dp)
            ) {
                Text(
                    when {
                        isActivelyMonitoring -> stringResource(R.string.view_monitoring)
                        isMonitoringEnabled -> stringResource(R.string.resume_monitoring)
                        else -> stringResource(R.string.start_monitoring)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (!isConfigured) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.configure_settings_first), color = MaterialTheme.colorScheme.error)
            } else if (!allPerms) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.missing_permissions), color = MaterialTheme.colorScheme.error)
                if (!camPerm) Text("• " + stringResource(R.string.camera_permission_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!notifPerm) Text("• " + stringResource(R.string.notification_permission_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (ignoresBattery) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.battery_optimization_unrestricted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.battery_optimization_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.battery_optimization_restricted), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { BatteryOptimization.showRequest(ctx) }) {
                            Text(stringResource(R.string.battery_optimization_action))
                        }
                    }
                }
            }
        }
    }
}

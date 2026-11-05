package org.grise.homeguard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.grise.homeguard.R
import org.grise.homeguard.viewmodel.MonitoringViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(onStop: () -> Unit, viewModel: MonitoringViewModel) {
    val elapsed by viewModel.elapsedTime.collectAsState()
    val remaining by viewModel.remainingTime.collectAsState()
    val currentInterval by viewModel.currentInterval.collectAsState()
    val connected by viewModel.isMatrixConnected.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.monitoring_active)) },
            navigationIcon = {
                IconButton(onClick = onStop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            }
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.elapsed_time, viewModel.formatTime(elapsed)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.remaining_time, viewModel.formatTime(remaining)), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.matrix_status, if (connected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(8.dp)
            ) {
                items(logs) { log ->
                    Text(log, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.stopMonitoring(); onStop() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.stop_monitoring))
            }
        }
    }
}

package org.grise.homeguard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.grise.homeguard.R
import org.grise.homeguard.util.Constants
import org.grise.homeguard.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()

    var matrixServerHost by remember { mutableStateOf(settings.matrixServerHost) }
    var matrixUsername by remember { mutableStateOf(settings.matrixUsername) }
    var matrixPassword by remember { mutableStateOf(settings.matrixPassword) }
    var recipientRoomId by remember { mutableStateOf(settings.recipientRoomId) }
    var sendIntervalStr by remember { mutableStateOf(settings.sendIntervalMinutes.toString()) }

    LaunchedEffect(settings) {
        if (matrixServerHost.isEmpty()) matrixServerHost = settings.matrixServerHost
        if (matrixUsername.isEmpty()) matrixUsername = settings.matrixUsername
        if (matrixPassword.isEmpty()) matrixPassword = settings.matrixPassword
        if (recipientRoomId.isEmpty()) recipientRoomId = settings.recipientRoomId
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.matrix_config),
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = matrixServerHost,
                onValueChange = {
                    matrixServerHost = it
                    viewModel.updateMatrixServerHost(it)
                },
                label = { Text(stringResource(R.string.server_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = matrixUsername,
                onValueChange = {
                    matrixUsername = it
                    viewModel.updateMatrixUsername(it)
                },
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = matrixPassword,
                onValueChange = {
                    matrixPassword = it
                    viewModel.updateMatrixPassword(it)
                },
                label = { Text(stringResource(R.string.password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.recipient),
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = recipientRoomId,
                onValueChange = {
                    recipientRoomId = it
                    viewModel.updateRecipientRoomId(it)
                },
                label = { Text(stringResource(R.string.recipient_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.send_frequency),
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = sendIntervalStr,
                onValueChange = {
                    sendIntervalStr = it
                    it.toIntOrNull()?.let { minutes ->
                        viewModel.updateSendIntervalMinutes(minutes)
                    }
                },
                label = { Text(stringResource(R.string.send_interval_minutes)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.motion_detection),
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = settings.isMotionDetectionEnabled,
                    onCheckedChange = { viewModel.updateMotionDetectionEnabled(it) }
                )
                Text(
                    text = stringResource(R.string.enable_motion_video),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.image_quality, settings.imageQuality),
                style = MaterialTheme.typography.titleLarge
            )

            Slider(
                value = settings.imageQuality.toFloat(),
                onValueChange = { viewModel.updateImageQuality(it.toInt()) },
                valueRange = Constants.IMAGE_QUALITY_RANGE,
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.motion_sensitivity, settings.motionThreshold),
                style = MaterialTheme.typography.titleLarge
            )

            Slider(
                value = settings.motionThreshold,
                onValueChange = { viewModel.updateMotionThreshold(it) },
                valueRange = Constants.MOTION_THRESHOLD_RANGE,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.cameras),
                style = MaterialTheme.typography.titleLarge
            )

            availableCameras.forEach { camera ->
                val isSelected = settings.selectedCameraIds.contains(camera.id)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            val currentIds = settings.selectedCameraIds.toMutableSet()
                            if (checked) {
                                currentIds.add(camera.id)
                            } else if (currentIds.size > 1) {
                                currentIds.remove(camera.id)
                            }
                            viewModel.updateSelectedCameraIds(currentIds)
                        }
                    )
                    Text(
                        text = "${stringResource(if (camera.isFront) R.string.camera_front else R.string.camera_back)} (${stringResource(R.string.camera_other, camera.id)})",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (settings.selectedCameraIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.camera_selection_required),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

package org.grise.homeguard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.grise.homeguard.R
import org.grise.homeguard.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()

    var matrixServerHost by remember { mutableStateOf(settings.matrixServerHost) }
    var matrixUsername by remember { mutableStateOf(settings.matrixUsername) }
    var matrixPassword by remember { mutableStateOf(settings.matrixPassword) }
    var recipientUsername by remember { mutableStateOf(settings.recipientUsername) }
    var sendIntervalMinutes by remember { mutableStateOf(settings.sendIntervalMinutes.toString()) }

    LaunchedEffect(settings) {
        matrixServerHost = settings.matrixServerHost
        matrixUsername = settings.matrixUsername
        matrixPassword = settings.matrixPassword
        recipientUsername = settings.recipientUsername
        sendIntervalMinutes = settings.sendIntervalMinutes.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title))
                },
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

        var matrixServerHost by rememberSaveable {
            mutableStateOf(settings.matrixServerHost)
        }
        var matrixUsername by rememberSaveable {
            mutableStateOf(settings.matrixUsername)
        }
        var matrixPassword by rememberSaveable {
            mutableStateOf(settings.matrixPassword)
        }
        var recipientUsername by rememberSaveable {
            mutableStateOf(settings.recipientUsername)
        }
        var sendIntervalMinutes by rememberSaveable {
            mutableIntStateOf(settings.sendIntervalMinutes)
        }
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
                value = recipientUsername,
                onValueChange = {
                    recipientUsername = it
                    viewModel.updateRecipientUsername(it)
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
                value = sendIntervalMinutes.toString(),
                onValueChange = {
                    sendIntervalMinutes = it.toInt()
                    it.toIntOrNull()?.let { minutes ->
                        viewModel.updateSendIntervalMinutes(minutes)
                    }
                },
                label = { Text(stringResource(R.string.send_interval_minutes)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

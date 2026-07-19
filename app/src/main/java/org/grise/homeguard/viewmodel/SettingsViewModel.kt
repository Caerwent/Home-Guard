package org.grise.homeguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.grise.homeguard.data.AppSettings
import org.grise.homeguard.data.SettingsDataStore

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = SettingsDataStore(application.applicationContext)
    
    val settings: StateFlow<AppSettings> = dataStore.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    fun updateMatrixServerHost(value: String) {
        viewModelScope.launch {
            dataStore.updateMatrixServerHost(value)
        }
    }

    fun updateMatrixUsername(value: String) {
        viewModelScope.launch {
            dataStore.updateMatrixUsername(value)
        }
    }

    fun updateMatrixPassword(value: String) {
        viewModelScope.launch {
            dataStore.updateMatrixPassword(value)
        }
    }

    fun updateRecipientUsername(value: String) {
        viewModelScope.launch {
            dataStore.updateRecipientUsername(value)
        }
    }

    fun updateSendIntervalMinutes(value: Int) {
        viewModelScope.launch {
            dataStore.updateSendIntervalMinutes(value)
        }
    }
}

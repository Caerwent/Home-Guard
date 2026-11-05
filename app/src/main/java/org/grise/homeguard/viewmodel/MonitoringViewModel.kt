package org.grise.homeguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.matrix.MatrixManager
import org.grise.homeguard.service.MonitoringService

class MonitoringViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = SettingsDataStore(application)

    val elapsedTime: StateFlow<Long> = MatrixManager.elapsedTime
    val remainingTime: StateFlow<Long> = MatrixManager.remainingTime
    val currentInterval: StateFlow<Int> = MatrixManager.currentInterval
    val isMatrixConnected: StateFlow<Boolean> = MatrixManager.connectionState

    /** True while [MonitoringService] is alive. */
    val isServiceRunning: StateFlow<Boolean> = MonitoringService.isRunning

    /** Which keep-awake locks the service currently holds. */
    val powerGuard: StateFlow<MonitoringService.PowerGuard> = MonitoringService.powerGuard

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        viewModelScope.launch { MatrixManager.logs.collect { _logs.value = it } }
    }

    fun startMonitoring() {
        viewModelScope.launch {
            val settings = dataStore.settings.first()
            if (!settings.isMonitoringEnabled) dataStore.updateMonitoringEnabled(true)
            MonitoringService.start(getApplication())
        }
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            dataStore.updateMonitoringEnabled(false)
            MonitoringService.stop(getApplication())
            MatrixManager.stop()
        }
    }

    override fun onCleared() {
    }

    fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

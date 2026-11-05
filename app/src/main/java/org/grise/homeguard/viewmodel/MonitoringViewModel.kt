package org.grise.homeguard.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.matrix.MatrixManager
import org.grise.homeguard.worker.MonitoringWorker
import java.util.concurrent.TimeUnit

class MonitoringViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = SettingsDataStore(application)
    private val workManager = WorkManager.getInstance(application)

    val elapsedTime = MatrixManager.elapsedTime
    val remainingTime = MatrixManager.remainingTime
    val currentInterval = MatrixManager.currentInterval
    val isMatrixConnected = MatrixManager.connectionState
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        viewModelScope.launch { MatrixManager.logs.collect { _logs.value = it } }
    }

    fun startMonitoring() {
        viewModelScope.launch {
            val settings = dataStore.settings.first()
            if (settings.isMonitoringEnabled) return@launch

            dataStore.updateMonitoringEnabled(true)
            val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueueUniqueWork(
                "monitoring",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        viewModelScope.launch { MatrixManager.logs.collect { _logs.value = it } }
    }

    fun stopMonitoring() {
        viewModelScope.launch {
            dataStore.updateMonitoringEnabled(false)
            workManager.cancelUniqueWork("monitoring")
            MatrixManager.stop()
        }
    }

    override fun onCleared() {
    }

    fun formatTime(seconds: Long): String {
        val h = TimeUnit.SECONDS.toHours(seconds)
        val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val s = seconds % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

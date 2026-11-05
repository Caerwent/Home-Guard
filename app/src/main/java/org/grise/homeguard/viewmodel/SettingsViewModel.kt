package org.grise.homeguard.viewmodel

import android.app.Application
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.grise.homeguard.data.AppSettings
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.util.Constants
import kotlin.time.Duration.Companion.milliseconds

data class CameraInfo(val id: String, val name: String, val isFront: Boolean)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = SettingsDataStore(application.applicationContext)
    
    private val _availableCameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCameras = _availableCameras.asStateFlow()

    private val updateJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            delay(1000.milliseconds)
            loadCameras()
        }
    }

    private fun loadCameras() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(getApplication())
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val cameras = cameraProvider.availableCameraInfos.mapIndexed { index, info ->
                        val id = index.toString()
                        val lensFacing = info.lensFacing
                        val isFront = lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                        CameraInfo(id, "Camera $id", isFront)
                    }
                    _availableCameras.value = cameras
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Failed to list cameras", e)
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        } catch (e: Exception) {
            Log.e(Constants.TAG, "ProcessCameraProvider error", e)
        }
    }
    
    val settings: StateFlow<AppSettings> = dataStore.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    private fun debounceUpdate(key: String, block: suspend () -> Unit) {
        updateJobs[key]?.cancel()
        updateJobs[key] = viewModelScope.launch {
            delay(500.milliseconds)
            block()
            updateJobs.remove(key)
        }
    }

    fun updateMatrixServerHost(value: String) {
        debounceUpdate("host") { dataStore.updateMatrixServerHost(value) }
    }

    fun updateMatrixUsername(value: String) {
        debounceUpdate("user") { dataStore.updateMatrixUsername(value) }
    }

    fun updateMatrixPassword(value: String) {
        debounceUpdate("pass") { dataStore.updateMatrixPassword(value) }
    }

    fun updateRecipientRoomId(value: String) {
        debounceUpdate("room") { dataStore.updateRecipientRoomId(value) }
    }

    fun updateSendIntervalMinutes(value: Int) {
        debounceUpdate("interval") { dataStore.updateSendIntervalMinutes(value) }
    }

    fun updateSelectedCameraIds(value: Set<String>) {
        viewModelScope.launch {
            dataStore.updateSelectedCameraIds(value)
        }
    }

    fun updateMotionDetectionEnabled(value: Boolean) {
        viewModelScope.launch {
            dataStore.updateMotionDetectionEnabled(value)
        }
    }

    fun updateImageQuality(value: Int) {
        debounceUpdate("quality") { dataStore.updateImageQuality(value) }
    }

    fun updateMotionThreshold(value: Float) {
        debounceUpdate("threshold") { dataStore.updateMotionThreshold(value) }
    }
}

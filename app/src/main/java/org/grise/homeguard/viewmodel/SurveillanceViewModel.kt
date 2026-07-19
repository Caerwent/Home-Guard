package org.grise.homeguard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class SurveillanceViewModel : ViewModel() {
    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    private val _remainingTime = MutableStateFlow(0L)
    val remainingTime: StateFlow<Long> = _remainingTime.asStateFlow()

    private val _isMatrixConnected = MutableStateFlow(false)
    val isMatrixConnected: StateFlow<Boolean> = _isMatrixConnected.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var timerJob: Job? = null

    fun startSurveillance(intervalMinutes: Int) {
        if (timerJob != null) return
        
        val intervalSeconds = intervalMinutes * 60L
        _remainingTime.value = intervalSeconds
        
        addLog("Surveillance session initialized (Interval: $intervalMinutes min)")

        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000.milliseconds)
                _elapsedTime.value += 1
                if (_remainingTime.value > 0) {
                    _remainingTime.value -= 1
                } else {
                    addLog("Taking picture...")
                    _remainingTime.value = intervalSeconds
                }
            }
        }
    }

    fun setMatrixConnected(connected: Boolean) {
        _isMatrixConnected.value = connected
        addLog(if (connected) "Matrix server connected" else "Matrix server disconnected")
    }

    fun stopSurveillance() {
        timerJob?.cancel()
        timerJob = null
        addLog("Surveillance stopped")
    }

    override fun onCleared() {
        stopSurveillance()
    }

    fun addLog(message: String) {
        _logs.value += message
    }

    fun formatTime(seconds: Long): String {
        val hours = TimeUnit.SECONDS.toHours(seconds)
        val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
        }
    }
}

package org.grise.homeguard.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.grise.homeguard.R
import org.grise.homeguard.camera.CameraHelper
import org.grise.homeguard.data.AppSettings
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.matrix.MatrixManager
import org.grise.homeguard.util.Constants
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

class MonitoringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), LifecycleOwner {

    companion object {
        const val ACTION_SHOW_MONITORING = "org.grise.homeguard.ACTION_SHOW_MONITORING"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val dataStore = SettingsDataStore(context)
    private val cameraHelper = CameraHelper(context)
    
    private var isRecording = false
    private var isMotionSequenceActive = false
    private var lastMotionTimestamp = 0L
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alarmMutex = Mutex()
    private val cameraFailureCount = mutableMapOf<String, Int>()

    override suspend fun doWork(): Result {
        Log.d(Constants.TAG, "Worker: Starting monitoring engine")
        createNotificationChannel()
        
        try {
            withContext(Dispatchers.Main) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
            
            try {
                setForeground(createForegroundInfo())
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Failed to set foreground (may be background restriction)", e)
            }
            
            val settings = dataStore.settings.first()
            if (!settings.isMonitoringEnabled) {
                Log.d(Constants.TAG, "Worker: Monitoring not enabled in settings, exiting")
                return Result.success()
            }

            MatrixManager.login(
                host = settings.matrixServerHost,
                user = settings.matrixUsername,
                pass = settings.matrixPassword,
                rid = settings.recipientRoomId,
                interval = settings.sendIntervalMinutes,
                onPhoto = {
                    val currentSettings = dataStore.settings.first()
                    performTimedCapture(currentSettings)
                },
                battery = ::getBattery,
                onAlarm = ::playAlarm,
                onIntervalChange = { newInterval ->
                    workerScope.launch { dataStore.updateSendIntervalMinutes(newInterval) }
                },
                onMotionChange = { enabled ->
                    workerScope.launch { dataStore.updateMotionDetectionEnabled(enabled) }
                }
            )

            val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            var motionDetected = false
            val motionListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null || isRecording) return
                    
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    
                    val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    val gForceChange = abs(magnitude - SensorManager.GRAVITY_EARTH)
                    
                    if (gForceChange > settings.motionThreshold) {
                        if (!isMotionSequenceActive) {
                            Log.i(Constants.TAG, "Motion detected! G-Force Change: $gForceChange")
                        }
                        motionDetected = true
                        lastMotionTimestamp = System.currentTimeMillis()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            var currentMotionEnabled = settings.isMotionDetectionEnabled

            if (currentMotionEnabled) {
                sensorManager.registerListener(motionListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            }

            while (true) {
                delay(Constants.MONITORING_CHECK_INTERVAL)
                val currentSettings = dataStore.settings.first()
                if (!currentSettings.isMonitoringEnabled) {
                    Log.d(Constants.TAG, "Worker: Monitoring disabled by user, stopping")
                    break
                }

                if (currentSettings.isMotionDetectionEnabled != currentMotionEnabled) {
                    currentMotionEnabled = currentSettings.isMotionDetectionEnabled
                    if (currentMotionEnabled) {
                        sensorManager.registerListener(motionListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                    } else {
                        sensorManager.unregisterListener(motionListener)
                    }
                }

                if (motionDetected && !isRecording && !isMotionSequenceActive) {
                    motionDetected = false
                    captureMotionSequence(currentSettings)
                }
            }

        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(Constants.TAG, "Worker: Error during execution", e)
                return Result.retry()
            }
        } finally {
            Log.d(Constants.TAG, "Worker: Cleaning up")
            MatrixManager.stop()
            cameraHelper.shutdown()
            withContext(Dispatchers.Main) {
                try {
                    if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
                    }
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Error destroying lifecycle", e)
                }
            }
        }

        return Result.success()
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "Active Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val intent = Intent(applicationContext, org.grise.homeguard.MainActivity::class.java).apply {
            action = ACTION_SHOW_MONITORING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.service_active))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            ForegroundInfo(Constants.NOTIFICATION_ID, notification, type)
        } else {
            ForegroundInfo(Constants.NOTIFICATION_ID, notification)
        }
    }

    private suspend fun captureMotionSequence(settings: AppSettings) {
        isMotionSequenceActive = true
        Log.i(Constants.TAG, "Starting motion capture sequence")
        try {
            while (System.currentTimeMillis() - lastMotionTimestamp < Constants.MOTION_COOLDOWN_MS) {
                performTimedCapture(settings, includeBattery = false)
                delay(Constants.MOTION_CAPTURE_DELAY)
            }
        } finally {
            isMotionSequenceActive = false
            Log.i(Constants.TAG, "Motion sequence finished")
        }
    }

    private suspend fun performTimedCapture(settings: AppSettings, includeBattery: Boolean = true) {
        val labeledFiles = mutableListOf<Pair<File, String>>()
        val failedCameras = mutableListOf<String>()
        
        val captureTime = measureTimeMillis {
            settings.selectedCameraIds.forEach { cameraId ->
                var captureSuccess = false
                var retryCount = 0
                val maxRetries = 1
                
                while (retryCount <= maxRetries && !captureSuccess) {
                    cameraHelper.takePhotoWithTimeout(
                        lifecycleOwner = this,
                        cameraId = cameraId,
                        quality = settings.imageQuality,
                        onPhotoCaptured = { 
                            labeledFiles.add(it to cameraId)
                            captureSuccess = true
                            cameraFailureCount[cameraId] = 0
                        },
                        onError = { 
                            Log.e(Constants.TAG, "Camera error $cameraId (attempt ${retryCount + 1})", it)
                            retryCount++
                            
                            if (retryCount > maxRetries) {
                                MatrixManager.addLog("Error camera $cameraId: ${it.message}")

                                val failures = (cameraFailureCount[cameraId] ?: 0) + 1
                                cameraFailureCount[cameraId] = failures
                                
                                if (failures >= 2) {
                                    Log.w(Constants.TAG, "Camera $cameraId failed $failures times, disabling")
                                    MatrixManager.addLog("Camera $cameraId disabled after $failures failures")
                                    workerScope.launch {
                                        val newCameraIds = settings.selectedCameraIds - cameraId
                                        dataStore.updateSelectedCameraIds(newCameraIds)
                                    }
                                }
                                
                                failedCameras.add(cameraId)
                            }
                        },
                        timeoutMs = 15000
                    )
                }
            }
        }

        var combined: File? = null
        val stitchTime = measureTimeMillis {
            combined = cameraHelper.combineImages(labeledFiles, settings.imageQuality, failedCameras)
        }

        val sendTime = measureTimeMillis {
            combined?.let {
                MatrixManager.sendImage(it, settings.recipientRoomId, includeBattery)
                if (labeledFiles.size > 1) {
                    labeledFiles.forEach { (file, _) -> if (file.exists()) file.delete() }
                }
            }
        }

        if (combined != null) {
            MatrixManager.addLog("Perf: Capture ${captureTime}ms, Stitch ${stitchTime}ms, Send ${sendTime}ms")
        }
    }

    private fun playAlarm() {
        workerScope.launch {
            if (alarmMutex.isLocked) return@launch
            alarmMutex.withLock {
                try {
                    val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    
                    val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

                    val toneGen = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    Log.i(Constants.TAG, "Playing siren...")

                    val startTime = System.currentTimeMillis()
                    while (coroutineContext.isActive && System.currentTimeMillis() - startTime < Constants.ALARM_DURATION_MS) {
                        toneGen.startTone(android.media.ToneGenerator.TONE_SUP_ERROR, 500)
                        delay(500.milliseconds)
                        if (!coroutineContext.isActive) break
                        toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                        delay(500.milliseconds)
                    }
                    
                    toneGen.release()
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Failed to play siren alert", e)
                }
            }
        }
    }

    private fun getBattery(): Int {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if ((level != -1) && (scale != -1)) (level * 100 / scale.toFloat()).toInt() else -1
    }
}

package org.grise.homeguard.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.grise.homeguard.R
import org.grise.homeguard.camera.CameraHelper
import org.grise.homeguard.data.AppSettings
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.matrix.MatrixManager
import org.grise.homeguard.notification.AppNotifications
import org.grise.homeguard.power.KeepAwakeController
import org.grise.homeguard.util.Constants
import org.grise.homeguard.util.ReentrantMutex
import org.grise.homeguard.util.gForceChangeOf
import org.grise.homeguard.util.isMotion
import org.grise.homeguard.util.shouldCaptureInBurst
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The monitoring engine.
 *
 * It used to run inside a `CoroutineWorker`: an expedited worker is not meant to live forever, the
 * CPU was free to suspend as soon as the screen went off, and once the device entered Doze the
 * periodic captures simply stopped. This service fixes that:
 *
 *  * it is a **foreground service** (`camera` type, which has no runtime quota, unlike `dataSync`)
 *    so the process is never cached and the camera stays usable with the screen off;
 *  * it holds a **partial wake lock** so the CPU never suspends, which also defers Doze;
 *  * it holds a **full power Wi-Fi lock** so the Wi-Fi radio stays up and the Matrix sync
 *    long-poll survives the screen being off;
 *  * it is `START_STICKY`, so the system re-creates it if it is ever killed for memory pressure.
 */
class MonitoringService : LifecycleService() {

    companion object {
        const val ACTION_SHOW_MONITORING = "org.grise.homeguard.ACTION_SHOW_MONITORING"
        const val ACTION_START = "org.grise.homeguard.action.START_MONITORING"
        const val ACTION_STOP = "org.grise.homeguard.action.STOP_MONITORING"

        private val _isRunning = MutableStateFlow(false)

        /** True while the engine is alive (used by the UI to offer "resume" affordances). */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _powerGuard = MutableStateFlow(PowerGuard())

        /** Which keep-awake locks are currently held. */
        val powerGuard: StateFlow<PowerGuard> = _powerGuard.asStateFlow()

        /** Starts monitoring; the caller is expected to be visible (camera FGS requirement). */
        fun start(context: Context) {
            startQuietly(context)
        }

        /**
         * Same as [start] but reports whether the system accepted the request instead of throwing.
         * The system refuses while the app is in the background
         * (`ForegroundServiceStartNotAllowedException`, API 31+) and on some OEM ROMs.
         */
        fun startQuietly(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java).setAction(ACTION_START)
            )
            true
        } catch (e: Exception) {
            Log.w(Constants.TAG, "The system refused to start the monitoring service", e)
            false
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Unable to request the monitoring service to stop", e)
            }
        }
    }

    data class PowerGuard(val cpuWakeLock: Boolean = false, val wifiWakeLock: Boolean = false)

    // Services are instantiated before ContextWrapper.attach(), so nothing that touches the
    // Context may be initialised in a property initializer: it must happen in onCreate.
    private lateinit var dataStore: SettingsDataStore
    private lateinit var cameraHelper: CameraHelper
    private lateinit var keepAwake: KeepAwakeController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engineJob: Job? = null
    private var notificationJob: Job? = null
    private var isForeground = false

    private val alarmMutex = Mutex()
    private val cameraFailureCount = mutableMapOf<String, Int>()

    /**
     * Serialises every capture (timed, motion, or remote `!picture`) against every other one.
     * Motion used to be discovered by polling `motionFlag` from the 1s loop, which made capture
     * implicitly single-threaded. Now that the sensor callback dispatches directly, two triggers
     * can genuinely overlap, so the exclusivity has to be explicit.
     */
    private val captureMutex = ReentrantMutex()

    @Volatile
    private var isMotionSequenceActive = false

    /** Wall-clock instant of the last accepted motion trigger, used to anchor the cooldown. */
    @Volatile
    private var lastMotionTimestamp = 0L

    /**
     * Motion is dispatched from the sensor callback into this channel, and consumed by
     * [startMotionCollector]. Conflated: while a burst is already being captured, later triggers
     * only need to signal "more motion happened", they must not queue up extra sequences.
     */
    private val motionChannel = Channel<Unit>(Channel.CONFLATED)

    private var motionJob: Job? = null

    private val sensorManager: SensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }

    /**
     * Runs on the sensor thread and must not block or suspend: it only records the trigger and
     * nudges [motionChannel]. The capture itself happens in [startMotionCollector].
     *
     * There is deliberately no `isMotionSequenceActive` guard here. Dropping triggers while a
     * sequence runs used to truncate the burst, because the collector decides how long to keep
     * shooting from [lastMotionTimestamp]; ignoring an update would make it stop early.
     */
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForceChange = gForceChangeOf(x, y, z)

            if (isMotion(gForceChange, motionThreshold)) {
                // The sensor is batched (see registerMotionListener), so events are delivered in
                // bursts with past timestamps: derive the real instant from the event itself
                // instead of System.currentTimeMillis(), otherwise the cooldown window drifts.
                lastMotionTimestamp = wallClockOf(event)
                if (!isMotionSequenceActive) {
                    Log.i(
                        Constants.TAG,
                        "Motion detected (g-force change $gForceChange), dispatching capture"
                    )
                }
                // trySend never blocks, which is what makes it safe on the sensor callback thread.
                motionChannel.trySend(Unit)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Converts a [SensorEvent.timestamp] (elapsedRealtimeNanos) to wall-clock milliseconds. */
    private fun wallClockOf(event: SensorEvent): Long =
        System.currentTimeMillis() -
                (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L

    @Volatile
    private var motionThreshold = Constants.MOTION_THRESHOLD_DEFAULT

    /** Whether the accelerometer listener is currently registered, so we toggle it only on change. */
    @Volatile
    private var motionListenerRegistered = false

    /**
     * Latest settings, refreshed by [startSettingsWatcher] so the 1s check loop no longer reads and
     * decodes the DataStore 86 400 times a day for values that almost never change.
     */
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private var settingsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dataStore = SettingsDataStore(this)
        cameraHelper = CameraHelper(this)
        keepAwake = KeepAwakeController(this)
        _isRunning.value = true
        Log.i(Constants.TAG, "Service: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            Log.i(Constants.TAG, "Service: stop requested")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) return START_NOT_STICKY

        keepAwake.acquire()
        _powerGuard.value = PowerGuard(keepAwake.isCpuWakeLockHeld, keepAwake.isWifiWakeLockHeld)
        AppNotifications.clearAttention(this)

        startEngine()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(Constants.TAG, "Service: destroyed")
        _isRunning.value = false
        _powerGuard.value = PowerGuard()

        notificationJob?.cancel()
        settingsJob?.cancel()
        motionJob?.cancel()
        engineJob?.cancel()
        serviceScope.cancel()
        motionChannel.close()

        try {
            sensorManager.unregisterListener(motionListener)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to unregister the sensor listener", e)
        }

        MatrixManager.stop()
        cameraHelper.shutdown()
        keepAwake.release()

        super.onDestroy()
    }

    /**
     * Brings the service to the foreground. Returns false when the system refused, in which case
     * the user is asked to tap a notification to resume (the only legal way to get camera access
     * back while the app is not visible).
     */
    private fun promoteToForeground(): Boolean {
        if (isForeground) return true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(Constants.TAG, "Service: camera permission missing, cannot run in foreground")
            MatrixManager.addLog("Camera permission missing")
            AppNotifications.showAttention(this, R.string.monitoring_paused_no_camera)
            stopSelf()
            return false
        }

        AppNotifications.ensureChannels(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(
                /* service = */ this,
                /* id = */ Constants.NOTIFICATION_ID,
                /* notification = */ AppNotifications.ongoingNotification(this, null),
                /* foregroundServiceType = */ type,
            )
            isForeground = true
            Log.i(Constants.TAG, "Service: running in foreground")
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+), IllegalStateException, ...
            Log.e(Constants.TAG, "Service: foreground start refused", e)
            MatrixManager.addLog("Foreground start refused: ${e.message}")
            AppNotifications.showAttention(this, R.string.monitoring_paused_text)
            stopSelf()
            false
        }
    }

    /** Supervises [runEngine] and restarts it with a backoff when it crashes. */
    private fun startEngine() {
        if (engineJob?.isActive == true) return

        engineJob = serviceScope.launch {
            var backoff = Constants.ENGINE_RETRY_MIN_DELAY_SECONDS
            while (isActive) {
                try {
                    runEngine()
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Engine crashed, restarting in ${backoff}s", e)
                    MatrixManager.stop()
                    MatrixManager.addLog("Engine error: ${e.message} - retry in ${backoff}s")
                    delay(backoff.seconds)
                    backoff = (backoff * 2).coerceAtMost(Constants.ENGINE_RETRY_MAX_DELAY_SECONDS)
                }
            }
        }
    }

    private suspend fun runEngine() {
        Log.d(Constants.TAG, "Engine: starting monitoring")

        val settings = dataStore.settings.first()
        if (!settings.isMonitoringEnabled) {
            Log.d(Constants.TAG, "Engine: monitoring not enabled in settings, stopping")
            stopSelf()
            return
        }

        motionThreshold = settings.motionThreshold
        latestSettings = settings
        startSettingsWatcher()
        startMotionCollector()
        startNotificationUpdater()

        MatrixManager.login(
            host = settings.matrixServerHost,
            user = settings.matrixUsername,
            pass = settings.matrixPassword,
            rid = settings.recipientRoomId,
            interval = settings.sendIntervalMinutes,
            onPhoto = {
                performTimedCapture(dataStore.settings.first())
            },
            battery = ::getBattery,
            onAlarm = ::playAlarm,
            onIntervalChange = { newInterval ->
                serviceScope.launch { dataStore.updateSendIntervalMinutes(newInterval) }
            },
            onMotionChange = { enabled ->
                serviceScope.launch { dataStore.updateMotionDetectionEnabled(enabled) }
            }
        )

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var ticks = 0L
        val startUptime = SystemClock.uptimeMillis()
        val startElapsed = SystemClock.elapsedRealtime()

        try {
            // Settings reactions (enable/disable, threshold) are driven by startSettingsWatcher,
            // which observes the DataStore StateFlow and therefore reacts immediately. This loop is
            // only a watchdog plus the heartbeat, so it no longer carries any latency budget.
            applyMotionDetectionSetting(settings.isMotionDetectionEnabled, accelerometer)

            while (currentCoroutineContext().isActive) {
                delay(Constants.MONITORING_CHECK_INTERVAL)
                ticks++

                if (ticks % Constants.HEARTBEAT_TICKS == 0L) {
                    logHeartbeat(ticks, startUptime, startElapsed)
                }

                if (!latestSettings.isMonitoringEnabled) {
                    Log.d(Constants.TAG, "Engine: monitoring disabled by user, stopping")
                    stopSelf()
                    break
                }
            }
        } finally {
            try {
                sensorManager.unregisterListener(motionListener)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Unable to unregister the sensor listener", e)
            }
        }
    }

    /**
     * Heartbeat diagnostics. Besides the sleep-time and wake-lock state, this reports the two
     * numbers needed to size the deferred "adaptive Wi-Fi lock" optimisation: how long the radio
     * lock has been held, and how often sync had to be re-established.
     */
    private fun logHeartbeat(ticks: Long, startUptime: Long, startElapsed: Long) {
        // uptimeMillis() stops advancing while the CPU is suspended, elapsedRealtime() does not:
        // their difference is exactly how long the device was asleep, which is what used to freeze
        // the capture loop.
        val suspended = (SystemClock.elapsedRealtime() - startElapsed) -
                (SystemClock.uptimeMillis() - startUptime)
        Log.i(
            Constants.TAG,
            "Engine heartbeat: ticks=$ticks, asleep=${suspended}ms, " +
                    "nextPhoto=${MatrixManager.remainingTime.value}s, " +
                    "cpu=${keepAwake.isCpuWakeLockHeld}, wifi=${keepAwake.isWifiWakeLockHeld}, " +
                    "wifiHeld=${keepAwake.wifiLockHeldDurationMs}ms, " +
                    "syncReconnects=${MatrixManager.syncReconnectCount.value}"
        )
    }

    /**
     * Consumes motion triggers dispatched by [motionListener] on the sensor thread.
     *
     * Because the trigger is delivered here as soon as the sensor callback fires, the time between
     * the device moving and the first photo is bounded by coroutine scheduling instead of the
     * polling interval that used to sit in front of it.
     */
    private fun startMotionCollector() {
        if (motionJob?.isActive == true) return

        motionJob = serviceScope.launch {
            while (isActive) {
                val delivered: ChannelResult<Unit> = motionChannel.receiveCatching()
                if (delivered.isClosed) break
                // The channel is conflated, so at most one trigger is pending. Drain the extras
                // that piled up while the previous sequence was running before deciding to capture.
                while (motionChannel.tryReceive().isSuccess) {
                    // Intentionally empty: the payload carries no data, only "motion happened".
                }

                if (!latestSettings.isMonitoringEnabled) continue

                // Measured here rather than inside the capture so it reflects trigger-to-start
                // latency, which is the budget that actually matters for "motion photo < 1s".
                val latency = System.currentTimeMillis() - lastMotionTimestamp
                if (latency > Constants.MOTION_DISPATCH_LATENCY_BUDGET_MS) {
                    Log.w(
                        Constants.TAG,
                        "Motion dispatch latency ${latency}ms exceeds the " +
                                "${Constants.MOTION_DISPATCH_LATENCY_BUDGET_MS}ms budget"
                    )
                } else if (latency > Constants.MOTION_DISPATCH_LATENCY_LOG_MS) {
                    Log.i(Constants.TAG, "Motion dispatch latency ${latency}ms")
                }

                runMotionSequence(latestSettings)
            }
        }
    }

    /**
     * Captures until [Constants.MOTION_COOLDOWN_MS] after the last motion trigger.
     *
     * The window is anchored on the instant the burst is picked up, and extended while
     * [motionListener] keeps reporting movement. Previously the window was compared against a
     * timestamp observed at the polling boundary, so a burst that ended just after a tick could
     * find the condition already false on entry and capture nothing at all.
     */
    private suspend fun runMotionSequence(settings: AppSettings) {
        if (!settings.isMotionDetectionEnabled) return

        captureMutex.withLock {
            val burstStart = System.currentTimeMillis()
            val triggeredAt = lastMotionTimestamp
            isMotionSequenceActive = true
            Log.i(Constants.TAG, "Starting motion capture sequence")
            try {
                while (shouldCaptureInBurst(
                        nowMs = System.currentTimeMillis(),
                        lastMotionMs = lastMotionTimestamp,
                        cooldownMs = Constants.MOTION_COOLDOWN_MS,
                        elapsedSinceStartMs = System.currentTimeMillis() - burstStart,
                    )
                ) {
                    // performTimedCapture re-enters captureMutex on this same coroutine, so the
                    // burst holds it for its whole duration and no other trigger can interleave.
                    performTimedCapture(settings, includeBattery = false)
                    delay(Constants.MOTION_CAPTURE_DELAY)
                }
            } finally {
                isMotionSequenceActive = false
                Log.i(
                    Constants.TAG,
                    "Motion sequence finished (last trigger ${System.currentTimeMillis() - triggeredAt}ms before end)"
                )
            }
        }
    }

    /**
     * Starts or stops the accelerometer to match [enabled]. Idempotent, and safe to call from both
     * the startup path and the settings collector.
     */
    private fun applyMotionDetectionSetting(enabled: Boolean, accelerometer: Sensor?) {
        if (enabled == motionListenerRegistered) return
        motionListenerRegistered = enabled
        if (enabled) {
            registerMotionListener(accelerometer)
        } else {
            try {
                sensorManager.unregisterListener(motionListener)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Unable to unregister the sensor listener", e)
            }
            Log.i(Constants.TAG, "Engine: motion detection disabled")
        }
    }

    private fun registerMotionListener(accelerometer: Sensor?) {
        if (accelerometer == null) {
            Log.w(Constants.TAG, "Engine: no accelerometer available for motion detection")
            return
        }
        // maxReportLatencyUs lets the hardware FIFO batch the samples, so the AP is woken once a
        // second instead of every 200ms. Nothing is lost: the loop only needs the peak deviation
        // within the last second, and each event still carries its own timestamp.
        sensorManager.registerListener(
            motionListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL,
            /* maxReportLatencyUs = */ Constants.MOTION_SENSOR_BATCH_US.toInt()
        )
        motionListenerRegistered = true
        Log.i(Constants.TAG, "Engine: motion detection enabled (${accelerometer.name}, batched)")
    }

    /**
     * Keeps [latestSettings] hot and reacts to changes as they happen.
     *
     * The DataStore already exposes settings as a `StateFlow`, so it emits on every write. The check
     * loop used to re-read those values once a second anyway, which added up to 86 400 redundant
     * reads a day and made every setting change up to a second late. Reacting here is both cheaper
     * and immediate.
     */
    private fun startSettingsWatcher() {
        if (settingsJob?.isActive == true) return

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        settingsJob = serviceScope.launch {
            dataStore.settings.collect { settings ->
                latestSettings = settings
                motionThreshold = settings.motionThreshold
                applyMotionDetectionSetting(settings.isMotionDetectionEnabled, accelerometer)
            }
        }
    }

    private fun startNotificationUpdater() {
        if (notificationJob?.isActive == true) return

        notificationJob = serviceScope.launch {
            var lastMinute = Long.MIN_VALUE
            MatrixManager.remainingTime.collect { remaining ->
                val minute = (remaining + 59) / 60
                if (minute != lastMinute) {
                    lastMinute = minute
                    updateNotification(remaining)
                }
            }
        }
    }

    private fun updateNotification(remainingSeconds: Long) {
        if (!isForeground) return
        AppNotifications.notifyMonitoring(this, formatCountdown(remainingSeconds))
    }

    private fun formatCountdown(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private suspend fun performTimedCapture(settings: AppSettings, includeBattery: Boolean = true) {
        // Reentrant: a motion burst already holds this lock for the duration of the sequence, while
        // a remote `!picture` arrives holding nothing. Either way only one capture touches the
        // camera at a time.
        captureMutex.withLock { performCapture(settings, includeBattery) }
    }

    private suspend fun performCapture(settings: AppSettings, includeBattery: Boolean) {
        val labeledFiles = mutableListOf<Pair<File, String>>()
        val failedCameras = mutableListOf<String>()

        val captureTime = measureTimeMillis {
            settings.selectedCameraIds.forEach { cameraId ->
                var captureSuccess = false
                var retryCount = 0
                val maxRetries = 1

                while (retryCount <= maxRetries && !captureSuccess) {
                    cameraHelper.takePhotoWithTimeout(
                        lifecycleOwner = this@MonitoringService,
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
                                    serviceScope.launch {
                                        val newCameraIds = dataStore.settings.first().selectedCameraIds - cameraId
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
        serviceScope.launch {
            if (alarmMutex.isLocked) return@launch
            alarmMutex.withLock {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                    val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

                    val toneGen = android.media.ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    Log.i(Constants.TAG, "Playing siren...")

                    val startTime = System.currentTimeMillis()
                    while (serviceScope.isActive &&
                        System.currentTimeMillis() - startTime < Constants.ALARM_DURATION_MS
                    ) {
                        toneGen.startTone(android.media.ToneGenerator.TONE_SUP_ERROR, 500)
                        delay(500.milliseconds)
                        if (!serviceScope.isActive) break
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
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if ((level != -1) && (scale != -1)) (level * 100 / scale.toFloat()).toInt() else -1
    }
}

package org.grise.homeguard.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import org.grise.homeguard.util.Constants

/**
 * Keeps the device from suspending while the monitoring engine is running.
 *
 * Two locks are held for the whole lifetime of [org.grise.homeguard.service.MonitoringService]:
 *
 *  * a `PARTIAL_WAKE_LOCK` which stops the CPU from entering suspend. Because the device is
 *    never idle while a wake lock is held, Doze is deferred indefinitely, so the capture loop
 *    keeps running with the screen off.
 *  * a full-power Wi-Fi lock so the Wi-Fi radio is not powered down (and idle-mode filtering is
 *    relaxed) while the CPU is asleep, which keeps the Matrix sync long-poll alive.
 *
 * Both locks are reference-counted=false so a single [release] is enough, and they are always
 * released together with the service to avoid a stuck wake lock.
 */
@Suppress("DEPRECATION") // WifiLock is the only public API to keep the Wi-Fi radio awake
class KeepAwakeController(context: Context) {

    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val cpuWakeLock: PowerManager.WakeLock = powerManager
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.CPU_WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    private val wifiWakeLock: WifiManager.WifiLock? = wifiManager?.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        },
        Constants.WIFI_WAKE_LOCK_TAG
    )?.apply { setReferenceCounted(false) }

    val isCpuWakeLockHeld: Boolean get() = cpuWakeLock.isHeld
    val isWifiWakeLockHeld: Boolean get() = wifiWakeLock?.isHeld == true

    fun acquire() {
        try {
            if (!cpuWakeLock.isHeld) cpuWakeLock.acquire()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Unable to acquire the CPU wake lock", e)
        }

        try {
            if (wifiWakeLock?.isHeld != true) wifiWakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Unable to acquire the Wi-Fi wake lock", e)
        }

        Log.i(
            Constants.TAG,
            "Keep awake engaged (cpu=$isCpuWakeLockHeld, wifi=$isWifiWakeLockHeld, " +
                    "deviceIdleMode=${powerManager.isDeviceIdleMode})"
        )
    }

    fun release() {
        try {
            if (cpuWakeLock.isHeld) cpuWakeLock.release()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to release the CPU wake lock", e)
        }

        try {
            if (wifiWakeLock?.isHeld == true) wifiWakeLock.release()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to release the Wi-Fi wake lock", e)
        }

        Log.i(Constants.TAG, "Keep awake released")
    }
}

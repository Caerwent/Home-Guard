package org.grise.homeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.grise.homeguard.R
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.notification.AppNotifications
import org.grise.homeguard.service.MonitoringService
import org.grise.homeguard.util.Constants

/**
 * Re-arms the monitoring engine after a reboot or an app update.
 *
 * Android does not allow a `camera` foreground service to be created by a `BOOT_COMPLETED`
 * receiver, so when the system refuses the start we fall back to an actionable notification: a
 * service started because the user tapped a notification *is* allowed to use the camera again.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.BOOT_WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(Constants.BOOT_WAKE_LOCK_TIMEOUT_MS)
            }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsDataStore(appContext).settings.first()
                if (!settings.isMonitoringEnabled) return@launch

                Log.i(Constants.TAG, "BootReceiver: monitoring was enabled, resuming")
                AppNotifications.ensureChannels(appContext)

                if (!MonitoringService.startQuietly(appContext)) {
                    AppNotifications.showAttention(appContext, R.string.monitoring_paused_text)
                }
            } catch (e: Exception) {
                Log.e(Constants.TAG, "BootReceiver: unable to resume monitoring", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }
}

package org.grise.homeguard.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.grise.homeguard.MainActivity
import org.grise.homeguard.R
import org.grise.homeguard.service.MonitoringService
import org.grise.homeguard.util.Constants

/**
 * Single place where every Home Guard notification is built:
 *
 *  * the ongoing notification of [MonitoringService] (channel `active`, low importance)
 *  * an actionable "monitoring paused" notification (channel `alerts`, high importance) used when
 *    the system refuses to bring the service to the foreground, for instance after a reboot: a
 *    `camera` foreground service cannot be created while the app is in the background, but it can
 *    be created when the user taps a notification.
 */
object AppNotifications {

    private const val REQUEST_OPEN = 10
    private const val REQUEST_START = 11
    private const val REQUEST_STOP = 12

    private const val pendingFlags =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_active),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.ALERT_CHANNEL_ID,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(true) }
        )
    }

    /** Ongoing notification shown while [MonitoringService] runs in the foreground. */
    fun ongoingNotification(context: Context, nextCapture: String?): Notification =
        NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.service_active))
            .setSubText(nextCapture?.let { context.getString(R.string.next_capture_in, it) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.stop_monitoring), serviceIntent(context, MonitoringService.ACTION_STOP, REQUEST_STOP))
            .build()

    /**
     * Publishes (or refreshes) the ongoing notification of [MonitoringService].
     *
     * @return true when the notification has been posted.
     */
    fun notifyMonitoring(context: Context, nextCapture: String?): Boolean {
        if (!canPostNotifications(context)) {
            Log.w(Constants.TAG, "Notifications are disabled, the service cannot run visibly")
            return false
        }
        return try {
            NotificationManagerCompat.from(context)
                .notify(Constants.NOTIFICATION_ID, ongoingNotification(context, nextCapture))
            true
        } catch (e: SecurityException) {
            Log.w(Constants.TAG, "POST_NOTIFICATIONS has been revoked", e)
            false
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to post the ongoing notification", e)
            false
        }
    }

    /**
     * Asks the user to resume the monitoring manually. Used when a foreground service start was
     * refused (background start restriction, missing camera permission, reboot...).
     */
    fun showAttention(context: Context, textRes: Int) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, Constants.ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.monitoring_paused_title))
            .setContentText(context.getString(textRes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(serviceIntent(context, MonitoringService.ACTION_START, REQUEST_START))
            .addAction(
                0,
                context.getString(R.string.resume_monitoring),
                serviceIntent(context, MonitoringService.ACTION_START, REQUEST_START)
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(Constants.ATTENTION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(Constants.TAG, "POST_NOTIFICATIONS has been revoked", e)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to post the attention notification", e)
        }
    }

    fun clearAttention(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(Constants.ATTENTION_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Unable to cancel the attention notification", e)
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MonitoringService.ACTION_SHOW_MONITORING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, pendingFlags)
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MonitoringService::class.java).setAction(action)
        return PendingIntent.getService(context, requestCode, intent, pendingFlags)
    }
}

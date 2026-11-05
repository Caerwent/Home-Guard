package org.grise.homeguard.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.grise.homeguard.util.Constants

/**
 * Helpers around the battery-optimization (Doze whitelist) state of the app.
 *
 * Even with a wake lock, aggressive OEM power managers can still freeze a background process.
 * Having the user mark the app as "unrestricted" is the only supported way to make continuous
 * capture reliable, and it also exempts the app from the foreground-service background start
 * restriction.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        Log.w(Constants.TAG, "Unable to read the battery optimization state", e)
        false
    }

    /**
     * Shows the dialog which lets the user drop the app out of battery optimization. Falls back to
     * the global optimization settings screen when the direct request is unavailable.
     *
     * @return true when a settings screen could be shown.
     */
    fun showRequest(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(direct)
            true
        } catch (e: Exception) {
            Log.d(Constants.TAG, "Direct battery optimization request unavailable", e)
            try {
                context.startActivity(fallback)
                true
            } catch (e2: Exception) {
                Log.w(Constants.TAG, "Battery optimization settings screen unavailable", e2)
                false
            }
        }
    }
}

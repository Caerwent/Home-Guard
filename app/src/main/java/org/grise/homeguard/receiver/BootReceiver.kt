package org.grise.homeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.grise.homeguard.data.SettingsDataStore
import org.grise.homeguard.worker.MonitoringWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("HomeGuard", "BootReceiver: Boot completed, checking monitoring state")
            val dataStore = SettingsDataStore(context)
            
            CoroutineScope(Dispatchers.IO).launch {
                val settings = dataStore.settings.first()
                if (settings.isMonitoringEnabled) {
                    Log.d("HomeGuard", "BootReceiver: Monitoring enabled, scheduling worker")
                    val request = OneTimeWorkRequestBuilder<MonitoringWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "monitoring",
                        ExistingWorkPolicy.KEEP,
                        request
                    )
                }
            }
        }
    }
}

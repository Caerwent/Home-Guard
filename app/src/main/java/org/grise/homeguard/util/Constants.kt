package org.grise.homeguard.util

import kotlin.time.Duration.Companion.seconds

object Constants {
    const val TAG = "HomeGuard"
    
    // Monitoring
    val MONITORING_CHECK_INTERVAL = 1.seconds
    const val INTERVAL_MINUTES_DEFAULT = 60
    val MOTION_COOLDOWN_MS = 15000L
    val MOTION_CAPTURE_DELAY = 3.seconds
    val MOTION_THRESHOLD_DEFAULT = 3.0f
    val MOTION_THRESHOLD_RANGE = 0.5f..10.0f
    
    // Camera
    const val IMAGE_STITCH_TARGET_HEIGHT = 720
    const val IMAGE_QUALITY_DEFAULT = 85
    val IMAGE_QUALITY_RANGE = 10f..100f
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "active"
    const val NOTIFICATION_ID = 1
    
    // Memory
    const val MAX_LOGS_COUNT = 50
    
    // Alarm
    const val ALARM_DURATION_MS = 10000L
}

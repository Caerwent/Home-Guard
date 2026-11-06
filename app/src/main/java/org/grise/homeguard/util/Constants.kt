package org.grise.homeguard.util

import kotlin.time.Duration.Companion.seconds

object Constants {
    const val TAG = "HomeGuard"
    
    // Monitoring
    // Watchdog tick: the engine loop now only supervises "service alive but engine dead" and
    // emits the heartbeat. Motion and settings are both event-driven, so nothing user-visible
    // depends on this interval any more.
    val MONITORING_CHECK_INTERVAL = 1.seconds
    const val INTERVAL_MINUTES_DEFAULT = 60
    val MOTION_COOLDOWN_MS = 15000L

    // Motion is dispatched straight from the sensor callback, so the trigger-to-capture latency
    // should be a few milliseconds. Anything above this budget is logged as a warning so the
    // "motion photo within 1s" requirement can be verified on real hardware.
    const val MOTION_DISPATCH_LATENCY_BUDGET_MS = 1000L
    const val MOTION_DISPATCH_LATENCY_LOG_MS = 100L
    val MOTION_CAPTURE_DELAY = 3.seconds
    val MOTION_THRESHOLD_DEFAULT = 3.0f
    val MOTION_THRESHOLD_RANGE = 0.5f..10.0f
    
    // Camera
    const val IMAGE_STITCH_TARGET_HEIGHT = 720
    const val IMAGE_QUALITY_DEFAULT = 85
    val IMAGE_QUALITY_RANGE = 10f..100f
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "active"
    const val ALERT_CHANNEL_ID = "alerts"
    const val NOTIFICATION_ID = 1
    const val ATTENTION_NOTIFICATION_ID = 2

    // Power / keep awake
    const val CPU_WAKE_LOCK_TAG = "HomeGuard::MonitoringEngine"
    const val WIFI_WAKE_LOCK_TAG = "HomeGuard::MonitoringWifi"

    // Lets the accelerometer FIFO batch samples so the AP is not woken for every 200ms event
    const val MOTION_SENSOR_BATCH_US = 1_000_000L
    const val BOOT_WAKE_LOCK_TAG = "HomeGuard::BootResume"
    const val BOOT_WAKE_LOCK_TIMEOUT_MS = 30_000L

    // Diagnostics: one heartbeat log per HEARTBEAT_TICKS iterations of the 1s watchdog loop
    const val HEARTBEAT_TICKS = 300L

    // Engine restart backoff
    const val ENGINE_RETRY_MIN_DELAY_SECONDS = 5L
    const val ENGINE_RETRY_MAX_DELAY_SECONDS = 60L
    
    // Memory
    const val MAX_LOGS_COUNT = 50
    
    // Alarm
    const val ALARM_DURATION_MS = 10000L

    // Granularity of the "elapsed monitoring time" counter shown on the monitoring screen. It is
    // display-only, so it ticks per minute instead of the 86 400 times a day it used to.
    const val ELAPSED_TICK_SECONDS = 60L
}

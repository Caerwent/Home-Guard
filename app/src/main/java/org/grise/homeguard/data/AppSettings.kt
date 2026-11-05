package org.grise.homeguard.data

import org.grise.homeguard.util.Constants

data class AppSettings(
    val matrixServerHost: String = "https://matrix.org",
    val matrixUsername: String = "",
    val matrixPassword: String = "",
    val recipientRoomId: String = "",
    val sendIntervalMinutes: Int = Constants.INTERVAL_MINUTES_DEFAULT,
    val isMonitoringEnabled: Boolean = false,
    val selectedCameraIds: Set<String> = setOf("0"),
    val isMotionDetectionEnabled: Boolean = false,
    val imageQuality: Int = Constants.IMAGE_QUALITY_DEFAULT,
    val motionThreshold: Float = Constants.MOTION_THRESHOLD_DEFAULT
) {
    fun isConfigured(): Boolean {
        return matrixUsername.isNotBlank() &&
                matrixPassword.isNotBlank() &&
                recipientRoomId.isNotBlank()
    }
}

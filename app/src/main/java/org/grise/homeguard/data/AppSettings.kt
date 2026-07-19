package org.grise.homeguard.data

data class AppSettings(
    val matrixServerHost: String = "https://matrix.org",
    val matrixUsername: String = "",
    val matrixPassword: String = "",
    val recipientUsername: String = "",
    val sendIntervalMinutes: Int = 60
) {
    fun isConfigured(): Boolean {
        return matrixUsername.isNotBlank() &&
                matrixPassword.isNotBlank() &&
                recipientUsername.isNotBlank()
    }
}

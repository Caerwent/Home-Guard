package org.grise.homeguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.grise.homeguard.util.Constants

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    
    private object PreferencesKeys {
        val MATRIX_SERVER_HOST = stringPreferencesKey("matrix_server_host")
        val MATRIX_USERNAME = stringPreferencesKey("matrix_username")
        val MATRIX_PASSWORD = stringPreferencesKey("matrix_password")
        val RECIPIENT_ROOM_ID = stringPreferencesKey("recipient_room_id")
        val SEND_INTERVAL_MINUTES = intPreferencesKey("send_interval_minutes")
        val IS_MONITORING_ENABLED = booleanPreferencesKey("is_monitoring_enabled")
        val SELECTED_CAMERA_IDS = stringSetPreferencesKey("selected_camera_ids")
        val IS_MOTION_DETECTION_ENABLED = booleanPreferencesKey("is_motion_detection_enabled")
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val MOTION_THRESHOLD = androidx.datastore.preferences.core.floatPreferencesKey("motion_threshold")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            matrixServerHost = preferences[PreferencesKeys.MATRIX_SERVER_HOST] ?: "https://matrix.org",
            matrixUsername = preferences[PreferencesKeys.MATRIX_USERNAME] ?: "",
            matrixPassword = preferences[PreferencesKeys.MATRIX_PASSWORD] ?: "",
            recipientRoomId = preferences[PreferencesKeys.RECIPIENT_ROOM_ID] ?: "",
            sendIntervalMinutes = preferences[PreferencesKeys.SEND_INTERVAL_MINUTES] ?: Constants.INTERVAL_MINUTES_DEFAULT,
            isMonitoringEnabled = preferences[PreferencesKeys.IS_MONITORING_ENABLED] ?: false,
            selectedCameraIds = preferences[PreferencesKeys.SELECTED_CAMERA_IDS] ?: setOf("0"),
            isMotionDetectionEnabled = preferences[PreferencesKeys.IS_MOTION_DETECTION_ENABLED] ?: false,
            imageQuality = preferences[PreferencesKeys.IMAGE_QUALITY] ?: Constants.IMAGE_QUALITY_DEFAULT,
            motionThreshold = preferences[PreferencesKeys.MOTION_THRESHOLD] ?: Constants.MOTION_THRESHOLD_DEFAULT
        )
    }
    
    suspend fun updateMatrixServerHost(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MATRIX_SERVER_HOST] = value
        }
    }
    
    suspend fun updateMatrixUsername(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MATRIX_USERNAME] = value
        }
    }
    
    suspend fun updateMatrixPassword(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MATRIX_PASSWORD] = value
        }
    }
    
    suspend fun updateRecipientRoomId(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECIPIENT_ROOM_ID] = value
        }
    }
    
    suspend fun updateSendIntervalMinutes(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEND_INTERVAL_MINUTES] = value
        }
    }

    suspend fun updateMonitoringEnabled(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_MONITORING_ENABLED] = value
        }
    }

    suspend fun updateSelectedCameraIds(value: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_CAMERA_IDS] = value
        }
    }

    suspend fun updateMotionDetectionEnabled(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_MOTION_DETECTION_ENABLED] = value
        }
    }

    suspend fun updateImageQuality(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMAGE_QUALITY] = value
        }
    }

    suspend fun updateMotionThreshold(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOTION_THRESHOLD] = value
        }
    }
}

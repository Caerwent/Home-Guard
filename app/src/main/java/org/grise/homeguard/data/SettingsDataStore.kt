package org.grise.homeguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {
    
    private object PreferencesKeys {
        val MATRIX_SERVER_HOST = stringPreferencesKey("matrix_server_host")
        val MATRIX_USERNAME = stringPreferencesKey("matrix_username")
        val MATRIX_PASSWORD = stringPreferencesKey("matrix_password")
        val RECIPIENT_USERNAME = stringPreferencesKey("recipient_username")
        val SEND_INTERVAL_MINUTES = intPreferencesKey("send_interval_minutes")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            matrixServerHost = preferences[PreferencesKeys.MATRIX_SERVER_HOST] ?: "https://matrix.org",
            matrixUsername = preferences[PreferencesKeys.MATRIX_USERNAME] ?: "",
            matrixPassword = preferences[PreferencesKeys.MATRIX_PASSWORD] ?: "",
            recipientUsername = preferences[PreferencesKeys.RECIPIENT_USERNAME] ?: "",
            sendIntervalMinutes = preferences[PreferencesKeys.SEND_INTERVAL_MINUTES] ?: 60
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
    
    suspend fun updateRecipientUsername(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECIPIENT_USERNAME] = value
        }
    }
    
    suspend fun updateSendIntervalMinutes(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEND_INTERVAL_MINUTES] = value
        }
    }
}

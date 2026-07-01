package dev.hogelog.kidappwatch

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val serverUrl: String = "",
    val deviceId: String = "",
    val apiToken: String = "",
    val extraHeaderName1: String = "",
    val extraHeaderValue1: String = "",
    val extraHeaderName2: String = "",
    val extraHeaderValue2: String = "",
    val lastEventSummary: String = "",
    val lastScanAtMillis: Long = 0L,
    val monitorEnabled: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val deviceId = stringPreferencesKey("device_id")
        val apiToken = stringPreferencesKey("api_token")
        val extraHeaderName1 = stringPreferencesKey("extra_header_name_1")
        val extraHeaderValue1 = stringPreferencesKey("extra_header_value_1")
        val extraHeaderName2 = stringPreferencesKey("extra_header_name_2")
        val extraHeaderValue2 = stringPreferencesKey("extra_header_value_2")
        val lastEventSummary = stringPreferencesKey("last_event_summary")
        val lastScanAtMillis = longPreferencesKey("last_scan_at_millis")
        val monitorEnabled = booleanPreferencesKey("monitor_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                serverUrl = preferences[Keys.serverUrl].orEmpty(),
                deviceId = preferences[Keys.deviceId].orEmpty(),
                apiToken = preferences[Keys.apiToken].orEmpty(),
                extraHeaderName1 = preferences[Keys.extraHeaderName1].orEmpty(),
                extraHeaderValue1 = preferences[Keys.extraHeaderValue1].orEmpty(),
                extraHeaderName2 = preferences[Keys.extraHeaderName2].orEmpty(),
                extraHeaderValue2 = preferences[Keys.extraHeaderValue2].orEmpty(),
                lastEventSummary = preferences[Keys.lastEventSummary].orEmpty(),
                lastScanAtMillis = preferences[Keys.lastScanAtMillis] ?: 0L,
                monitorEnabled = preferences[Keys.monitorEnabled] ?: true,
            )
        }

    suspend fun saveConnection(
        serverUrl: String,
        deviceId: String,
        apiToken: String,
        extraHeaderName1: String,
        extraHeaderValue1: String,
        extraHeaderName2: String,
        extraHeaderValue2: String,
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.serverUrl] = serverUrl.trim().trimEnd('/')
            preferences[Keys.deviceId] = deviceId.trim()
            preferences[Keys.apiToken] = apiToken.trim()
            preferences[Keys.extraHeaderName1] = extraHeaderName1.trim()
            preferences[Keys.extraHeaderValue1] = extraHeaderValue1.trim()
            preferences[Keys.extraHeaderName2] = extraHeaderName2.trim()
            preferences[Keys.extraHeaderValue2] = extraHeaderValue2.trim()
        }
    }

    suspend fun saveLastEvent(summary: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.lastEventSummary] = summary
        }
    }

    suspend fun saveLastScanAt(millis: Long) {
        context.dataStore.edit { preferences ->
            preferences[Keys.lastScanAtMillis] = millis
        }
    }
}

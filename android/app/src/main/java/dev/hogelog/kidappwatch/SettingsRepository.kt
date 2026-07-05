package dev.hogelog.kidappwatch

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.Preferences
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
    val extraHeaders: String = "",
    val lastEventSummary: String = "",
    val lastEventSummaries: List<String> = emptyList(),
    val lastCheckSummary: String = "",
    val lastScanAtMillis: Long = 0L,
    val monitorEnabled: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val deviceId = stringPreferencesKey("device_id")
        val extraHeaders = stringPreferencesKey("extra_headers")
        val extraHeaderName1 = stringPreferencesKey("extra_header_name_1")
        val extraHeaderValue1 = stringPreferencesKey("extra_header_value_1")
        val extraHeaderName2 = stringPreferencesKey("extra_header_name_2")
        val extraHeaderValue2 = stringPreferencesKey("extra_header_value_2")
        val lastEventSummary = stringPreferencesKey("last_event_summary")
        val lastEventSummaries = stringPreferencesKey("last_event_summaries")
        val lastCheckSummary = stringPreferencesKey("last_check_summary")
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
                deviceId = preferences[Keys.deviceId].orEmpty().ifBlank { defaultDeviceId() },
                extraHeaders = preferences[Keys.extraHeaders] ?: legacyExtraHeaders(preferences),
                lastEventSummary = preferences[Keys.lastEventSummary].orEmpty(),
                lastEventSummaries = preferences[Keys.lastEventSummaries]
                    ?.lines()
                    ?.filter { it.isNotBlank() }
                    ?: preferences[Keys.lastEventSummary]
                        .orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let { listOf(it) }
                        .orEmpty(),
                lastCheckSummary = preferences[Keys.lastCheckSummary].orEmpty(),
                lastScanAtMillis = preferences[Keys.lastScanAtMillis] ?: 0L,
                monitorEnabled = preferences[Keys.monitorEnabled] ?: true,
            )
        }

    suspend fun saveConnection(
        serverUrl: String,
        extraHeaders: String,
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.serverUrl] = serverUrl.trim().trimEnd('/')
            preferences[Keys.deviceId] = defaultDeviceId()
            preferences[Keys.extraHeaders] = extraHeaders.trim()
        }
    }

    suspend fun saveCheckStatus(summary: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.lastCheckSummary] = summary
        }
    }

    suspend fun saveLastEvent(summary: String) {
        context.dataStore.edit { preferences ->
            val recent = preferences[Keys.lastEventSummaries]
                ?.lines()
                ?.filter { it.isNotBlank() }
                .orEmpty()
            preferences[Keys.lastEventSummary] = summary
            preferences[Keys.lastEventSummaries] = (listOf(summary) + recent)
                .distinct()
                .take(20)
                .joinToString("\n")
        }
    }

    suspend fun saveLastScanAt(millis: Long) {
        context.dataStore.edit { preferences ->
            preferences[Keys.lastScanAtMillis] = millis
        }
    }

    private fun defaultDeviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        return "android-$androidId"
    }

    private fun legacyExtraHeaders(preferences: Preferences): String = listOf(
        preferences[Keys.extraHeaderName1].orEmpty() to preferences[Keys.extraHeaderValue1].orEmpty(),
        preferences[Keys.extraHeaderName2].orEmpty() to preferences[Keys.extraHeaderValue2].orEmpty(),
    )
        .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
        .joinToString("\n") { (name, value) -> "$name: $value" }
}

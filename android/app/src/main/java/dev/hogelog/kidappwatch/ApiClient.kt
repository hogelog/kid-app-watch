package dev.hogelog.kidappwatch

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

data class WatchPackage(
    val packageName: String,
    val appLabel: String,
    val cooldownSeconds: Long,
)

class ApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun fetchConfig(settings: AppSettings): List<WatchPackage> {
        val request = Request.Builder()
            .url("${settings.serverUrl}/api/devices/${settings.deviceId}/config")
            .header("Authorization", "Bearer ${settings.apiToken}")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Config request failed: HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val packages = JSONObject(body).getJSONArray("watch_packages")
            return packages.toWatchPackages()
        }
    }

    fun postAppLaunchEvent(
        settings: AppSettings,
        packageName: String,
        appLabel: String,
        detectedAt: Instant,
    ) {
        val body = JSONObject()
            .put("package_name", packageName)
            .put("app_label", appLabel)
            .put("detected_at", DateTimeFormatter.ISO_INSTANT.format(detectedAt))
            .put("source", "usage_stats")
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${settings.serverUrl}/api/devices/${settings.deviceId}/app_launch_events")
            .header("Authorization", "Bearer ${settings.apiToken}")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Event request failed: HTTP ${response.code}")
        }
    }

    private fun JSONArray.toWatchPackages(): List<WatchPackage> = buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                WatchPackage(
                    packageName = item.getString("package_name"),
                    appLabel = item.optString("app_label", item.getString("package_name")),
                    cooldownSeconds = item.optLong("cooldown_seconds", 300L),
                ),
            )
        }
    }
}

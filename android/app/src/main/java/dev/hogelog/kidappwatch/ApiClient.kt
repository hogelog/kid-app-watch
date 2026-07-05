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
            .withAuthHeaders(settings)
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
        durationSeconds: Long?,
    ) {
        val body = JSONObject()
            .put("package_name", packageName)
            .put("app_label", appLabel)
            .put("detected_at", DateTimeFormatter.ISO_INSTANT.format(detectedAt))
            .put("source", "usage_stats")
            .apply {
                if (durationSeconds != null && durationSeconds > 0) {
                    put("duration_seconds", durationSeconds)
                }
            }
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${settings.serverUrl}/api/devices/${settings.deviceId}/app_launch_events")
            .withAuthHeaders(settings)
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

    private fun Request.Builder.withAuthHeaders(settings: AppSettings): Request.Builder {
        for ((name, value) in parseExtraHeaders(settings.extraHeaders)) {
            header(name, value)
        }
        return this
    }

    private fun parseExtraHeaders(rawHeaders: String): List<Pair<String, String>> = rawHeaders
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null

            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toList()
}

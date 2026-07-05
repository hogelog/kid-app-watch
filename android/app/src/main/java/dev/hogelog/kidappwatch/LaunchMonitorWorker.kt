package dev.hogelog.kidappwatch

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LaunchMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = SettingsRepository(appContext)
    private val apiClient = ApiClient()

    override suspend fun doWork(): Result {
        val settings = repository.settings.first()
        val showStatus = inputData.getBoolean(SHOW_STATUS_INPUT_KEY, false)
        if (!settings.monitorEnabled) return Result.success()
        if (settings.serverUrl.isBlank()) {
            if (showStatus) repository.saveCheckStatus("Check skipped: Server URL is empty")
            return Result.success()
        }
        if (!UsageAccessHelper.hasUsageAccess(applicationContext)) {
            if (showStatus) repository.saveCheckStatus("Check skipped: Usage access is not granted")
            return Result.success()
        }

        return runCatching {
            val watchPackages = apiClient.fetchConfig(settings)
            val watchPackageByName = watchPackages.associateBy { it.packageName }
            val now = System.currentTimeMillis()
            val scanStart = when {
                settings.lastScanAtMillis > 0L -> settings.lastScanAtMillis
                else -> now - INITIAL_LOOKBACK_MILLIS
            }

            val usageEvents = readUsageEvents(scanStart, now)
            val launches = usageEvents
                .filter { it.isLaunch && it.packageName in watchPackageByName }
                .distinctBy { it.packageName }

            var sentCount = 0
            for (launch in launches) {
                val watchPackage = watchPackageByName.getValue(launch.packageName)
                val durationSeconds = estimateDurationSeconds(launch, usageEvents, now)
                apiClient.postAppLaunchEvent(
                    settings = settings,
                    packageName = launch.packageName,
                    appLabel = watchPackage.appLabel,
                    detectedAt = Instant.ofEpochMilli(launch.timestampMillis),
                    durationSeconds = durationSeconds,
                )
                sentCount += 1
                repository.saveLastEvent(
                    "${watchPackage.appLabel} at ${formatLocalMinute(launch.timestampMillis)}" +
                        (durationSeconds?.let { " (${formatDuration(it)})" } ?: ""),
                )
            }

            repository.saveLastScanAt(now)
            if (showStatus) repository.saveCheckStatus("Checked ${formatLocalMinute(now)}: $sentCount sent")
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (showStatus) repository.saveCheckStatus("Check failed: ${error.message ?: error::class.java.simpleName}")
                Result.retry()
            },
        )
    }

    private fun readUsageEvents(startMillis: Long, endMillis: Long): List<UsageEvent> {
        val usageStatsManager = applicationContext.getSystemService(UsageStatsManager::class.java)
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val usageEvents = mutableListOf<UsageEvent>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                usageEvents += UsageEvent(event.packageName, event.timeStamp, event.eventType)
            }
        }

        return usageEvents.sortedByDescending { it.timestampMillis }
    }

    private fun estimateDurationSeconds(
        launch: UsageEvent,
        usageEvents: List<UsageEvent>,
        scanEndMillis: Long,
    ): Long? {
        val backgroundAt = usageEvents
            .asSequence()
            .filter { event ->
                event.packageName == launch.packageName &&
                    event.timestampMillis > launch.timestampMillis &&
                    event.isBackground
            }
            .minOfOrNull { it.timestampMillis }
            ?: scanEndMillis

        return ((backgroundAt - launch.timestampMillis) / 1000L).takeIf { it > 0 }
    }

    private fun formatLocalMinute(timestampMillis: Long): String = DateTimeFormatter
        .ofPattern("M/d HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMillis))

    private fun formatDuration(seconds: Long): String {
        val minutes = Math.round(seconds / 60.0)
        if (minutes <= 0) return "<1 min"
        if (minutes < 60) return "$minutes min"

        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes == 0L) "$hours h" else "$hours h $remainingMinutes min"
    }

    private data class UsageEvent(
        val packageName: String,
        val timestampMillis: Long,
        val eventType: Int,
    ) {
        val isLaunch: Boolean
            get() = eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                eventType == UsageEvents.Event.MOVE_TO_FOREGROUND

        val isBackground: Boolean
            get() = eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
    }

    companion object {
        const val SHOW_STATUS_INPUT_KEY = "show_status"
        private const val INITIAL_LOOKBACK_MILLIS = 15 * 60 * 1000L
    }
}

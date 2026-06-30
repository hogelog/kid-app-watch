package dev.hogelog.kidappwatch

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.Instant

class LaunchMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = SettingsRepository(appContext)
    private val apiClient = ApiClient()

    override suspend fun doWork(): Result {
        val settings = repository.settings.first()
        if (!settings.monitorEnabled) return Result.success()
        if (settings.serverUrl.isBlank() || settings.deviceId.isBlank() || settings.apiToken.isBlank()) {
            return Result.success()
        }
        if (!UsageAccessHelper.hasUsageAccess(applicationContext)) {
            repository.saveLastEvent("Usage access is not granted")
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

            val launches = readLaunchEvents(scanStart, now)
                .filter { it.packageName in watchPackageByName }
                .distinctBy { it.packageName }

            for (launch in launches) {
                val watchPackage = watchPackageByName.getValue(launch.packageName)
                apiClient.postAppLaunchEvent(
                    settings = settings,
                    packageName = launch.packageName,
                    appLabel = watchPackage.appLabel,
                    detectedAt = Instant.ofEpochMilli(launch.timestampMillis),
                )
                repository.saveLastEvent("${watchPackage.appLabel} at ${Instant.ofEpochMilli(launch.timestampMillis)}")
            }

            repository.saveLastScanAt(now)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                repository.saveLastEvent(error.message ?: error::class.java.simpleName)
                Result.retry()
            },
        )
    }

    private fun readLaunchEvents(startMillis: Long, endMillis: Long): List<LaunchEvent> {
        val usageStatsManager = applicationContext.getSystemService(UsageStatsManager::class.java)
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val launches = mutableListOf<LaunchEvent>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                launches += LaunchEvent(event.packageName, event.timeStamp)
            }
        }

        return launches.sortedByDescending { it.timestampMillis }
    }

    private data class LaunchEvent(
        val packageName: String,
        val timestampMillis: Long,
    )

    private companion object {
        const val INITIAL_LOOKBACK_MILLIS = 15 * 60 * 1000L
    }
}

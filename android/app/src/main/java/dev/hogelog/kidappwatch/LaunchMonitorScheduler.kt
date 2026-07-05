package dev.hogelog.kidappwatch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object LaunchMonitorScheduler {
    private const val WORK_NAME = "launch-monitor"
    private const val CHECK_NOW_WORK_NAME = "launch-monitor-check-now"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<LaunchMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueCheckNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LaunchMonitorWorker>()
            .setConstraints(networkConstraints())
            .setInputData(workDataOf(LaunchMonitorWorker.SHOW_STATUS_INPUT_KEY to true))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK_NOW_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

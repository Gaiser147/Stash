package com.stash.data.download.acquisition

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MuseAcquisitionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MuseAcquisitionPreferences,
) {
    suspend fun refreshSchedule() {
        val config = prefs.current()
        val workManager = WorkManager.getInstance(context)
        if (!config.configured) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(MANUAL_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<MuseAcquisitionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(config))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    suspend fun enqueueNow() {
        val config = prefs.current()
        if (!config.configured) return
        val request = OneTimeWorkRequestBuilder<MuseAcquisitionWorker>()
            .setConstraints(constraints(config))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal fun constraints(config: MuseAcquisitionConfig): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(config.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        const val PERIODIC_WORK_NAME = "muse-acquisition-periodic"
        const val MANUAL_WORK_NAME = "muse-acquisition-manual"
    }
}

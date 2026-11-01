package com.bortho.autovelox.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bortho.autovelox.data.SettingsStore
import com.bortho.autovelox.data.remote.SyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun syncEngine(): SyncEngine
    fun settings(): SettingsStore
}

/** Phone only: replays the local queue to Firestore and refreshes the user's blacklist and settings. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val drained = entry.syncEngine().drain()
        entry.syncEngine().pullUserState()?.let { entry.settings().saveAlertSettings(it) }
        return if (drained) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "vapian-sync"

        /** Runs every 6 hours, only with a connection; a missed run simply happens when the network returns. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

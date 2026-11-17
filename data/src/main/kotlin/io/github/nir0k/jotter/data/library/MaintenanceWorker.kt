package io.github.nir0k.jotter.data.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import io.github.nir0k.jotter.data.JotterServices

/** The unique name of the daily maintenance work. */
private const val MaintenanceWork = "maintenance"

/**
 * MaintenanceWorker removes, once a day in the background, what the open library keeps no
 * longer: notes trashed longer than the retention period and attachment files unreferenced for
 * 30 days.
 */
class MaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val library = (applicationContext as JotterServices).library
        // A process started for this work opens the library first.
        library.current.first { it !is LibraryState.Opening }
        library.maintain()
        return Result.success()
    }

    companion object {
        /** Schedules the daily run; an existing schedule is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(MaintenanceWork, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

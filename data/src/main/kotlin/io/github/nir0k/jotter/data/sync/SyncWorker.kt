package io.github.nir0k.jotter.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.nir0k.jotter.data.settings.MeteredSync
import io.github.nir0k.jotter.data.settings.SyncSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import io.github.nir0k.jotter.data.JotterServices

/** The unique work of runs the application asks for. */
private const val RunWork = "sync"

/** The unique periodic work of background runs. */
private const val IntervalWork = "sync-interval"

/** The unique work of a run waiting for an unmetered network. */
private const val UnmeteredWork = "sync-unmetered"

/** The input flag of a background run. */
private const val BackgroundKey = "background"

/** A run lasting longer than this shows the ongoing sync notification. */
private const val LongRunMs = 3_000L

/**
 * SyncWorker runs synchronisation for WorkManager, so a run that has started finishes even when
 * the application goes to the background: runs the application asks for go through expedited work,
 * the interval's runs through periodic work with the chosen network, and a run left for an
 * unmetered network through work waiting for one. A run lasting longer than
 * a moment becomes a foreground service with the ongoing sync notification, as Android requires.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val sync = (applicationContext as JotterServices).sync
        coroutineScope {
            val ongoing = launch {
                delay(LongRunMs)
                // Read once: the notification changes with every file.
                val accent = SyncNotifications.accent(applicationContext)
                // Android refuses a foreground service to a background start on newer versions;
                // the run then goes on as ordinary work.
                runCatching { setForeground(foregroundInfo(accent)) }
                sync.status.collect { SyncNotifications.updateOngoing(applicationContext, it.done, it.total, accent) }
            }
            try {
                sync.runPending(background = inputData.getBoolean(BackgroundKey, false))
            } finally {
                // The progress is posted from another thread: once it has stopped, nothing posts the
                // notification again. A run Android kept from the foreground posted it as an
                // ordinary notification, which WorkManager does not remove.
                ongoing.cancelAndJoin()
                SyncNotifications.cancelOngoing(applicationContext)
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(SyncNotifications.accent(applicationContext))

    /** Returns the foreground service of a run with its ongoing notification, before the progress is known. */
    private fun foregroundInfo(accent: Int) = ForegroundInfo(
        SyncNotifications.OngoingId,
        SyncNotifications.ongoing(applicationContext, 0, 0, accent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        /**
         * Asks for a run as soon as possible; a request during a run is queued behind it, and the
         * queued worker finds nothing to do when the run already took the request.
         */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RunWork, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Asks for a background run once an unmetered network is available, for runs that waited
         * for one or attachments left on a metered network; a run already waiting is kept.
         */
        fun runWhenUnmetered(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInputData(workDataOf(BackgroundKey to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UnmeteredWork, ExistingWorkPolicy.KEEP, request)
        }

        /** Cancels the run waiting for an unmetered network, when the library is disconnected. */
        fun cancelUnmetered(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UnmeteredWork)
        }

        /**
         * Schedules the background runs of the open library at its interval, on any network or,
         * when runs wait on metered networks, only unmetered ones; or cancels them for a library
         * without a connection or with Off.
         */
        fun schedule(context: Context, settings: SyncSettings?) {
            val work = WorkManager.getInstance(context)
            if (settings == null || settings.interval <= 0) {
                work.cancelUniqueWork(IntervalWork)
                return
            }
            val network = if (settings.metered == MeteredSync.Off) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<SyncWorker>(settings.interval.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInitialDelay(settings.interval.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(BackgroundKey to true))
                .build()
            work.enqueueUniquePeriodicWork(IntervalWork, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

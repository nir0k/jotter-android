package io.github.nir0k.jotter.data.sync

import android.content.Context
import android.net.ConnectivityManager
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.removePath
import io.github.nir0k.jotter.core.sftp.HostKey
import io.github.nir0k.jotter.core.sftp.HostKeyException
import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SftpSecret
import io.github.nir0k.jotter.core.sftp.SftpStorage
import io.github.nir0k.jotter.core.sftp.SshKey
import io.github.nir0k.jotter.core.sync.SyncOptions
import io.github.nir0k.jotter.core.sync.SyncReport
import io.github.nir0k.jotter.core.sync.SyncRun
import io.github.nir0k.jotter.core.sync.SyncState
import io.github.nir0k.jotter.core.sync.checkStorageFor
import io.github.nir0k.jotter.core.sync.remoteLibraryId
import io.github.nir0k.jotter.core.sync.synchronise
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.settings.DevicePreferences
import io.github.nir0k.jotter.data.settings.MeteredSync
import io.github.nir0k.jotter.data.settings.PausedUntilResumed
import io.github.nir0k.jotter.data.settings.SyncSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.data.R
import io.github.nir0k.jotter.data.settings.inAppLanguage

/** What synchronisation of the open library is doing. */
enum class SyncPhase {
    /** Not connected, or between runs after a successful one. */
    Idle,

    /** A run or a download is going on. */
    Running,

    /** Another device holds the lock; the run is tried again shortly. */
    Waiting,

    /** The last run failed; the status says why. */
    Failed,
}

/**
 * SyncStatus is what the Sync tab of Settings, the status banner and pull to refresh show of the open
 * library's synchronisation.
 *
 * @param libraryId The open library, or null without one.
 * @param settings The library's connection and preferences, or null when it has no sync storage.
 * @param done Files done in the running run.
 * @param total Files to do in the running run.
 * @param deferredAttachments Attachments the last run left for an unmetered network.
 * @param error Why the last run failed or waits, or null.
 * @param hostKey The server's key, when the last run stopped because it is unknown or changed.
 * @param log The most recent runs, the latest first.
 */
data class SyncStatus(
    val libraryId: String? = null,
    val settings: SyncSettings? = null,
    val phase: SyncPhase = SyncPhase.Idle,
    val done: Int = 0,
    val total: Int = 0,
    val deferredAttachments: Int = 0,
    val error: ErrorCode? = null,
    val hostKey: HostKey? = null,
    val log: List<SyncRun> = emptyList(),
)

/**
 * Conflict copies a run made, announced in a snackbar that opens the first one.
 *
 * @param count The number of conflict copies of notes and attachments.
 * @param firstNote The first conflicting note's copy, or null when only attachments conflicted.
 */
data class SyncConflicts(val count: Int, val firstNote: String?)

/** What a remote folder holds, as Browse tells it. */
enum class FolderContents { Library, Empty, Other }

/**
 * One folder of the server as Browse lists it.
 *
 * @param path The folder's absolute path.
 * @param folders The names of its subfolders, in name order.
 */
data class FolderListing(val path: String, val folders: List<String>, val contents: FolderContents)

/**
 * RemoteBrowser lists and creates folders on an SFTP server for Browse, through a connection to
 * its root folder that stays open while the dialog shows; it must be closed.
 */
class RemoteBrowser internal constructor(private val storage: SftpStorage) : AutoCloseable {
    /**
     * Lists a folder: its subfolders and whether it holds a library, nothing or other files.
     *
     * @param path The folder, absolute or relative to the home folder; empty for the home folder.
     * @throws FormatException NotFound, Network or Io.
     */
    suspend fun list(path: String): FolderListing = withContext(Dispatchers.IO) {
        val absolute = storage.resolveFolder(path)
        val entries = storage.list(absolute.removePrefix("/"))
        val visible = entries.filter { !it.name.startsWith(".jotter-") }
        val contents = when {
            entries.any { it.name == "library.json" && !it.folder } -> FolderContents.Library
            visible.isEmpty() -> FolderContents.Empty
            else -> FolderContents.Other
        }
        FolderListing(absolute, entries.filter { it.folder && !it.name.startsWith(".") }.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER), contents)
    }

    /**
     * Creates a folder inside another.
     *
     * @return The new folder's absolute path.
     * @throws FormatException Network or Io.
     */
    suspend fun create(parent: String, name: String): String = withContext(Dispatchers.IO) {
        val path = "${parent.trimEnd('/')}/$name"
        storage.makeFolder(path.removePrefix("/"))
        path
    }

    override fun close() = storage.close()
}

/** The minutes after a run that found another device's lock before the next try (desktop spec section 12.3). */
private const val LockedRetryMs = 30_000L

/** A run starts this long after the last local change. */
private const val ChangeDelayMs = 30_000L

/** Network failures are retried after 1, 2, 4 and more minutes, up to the interval or this many minutes without one. */
private const val MaxRetryMinutes = 15

/** How often a run that left locally changed files is repeated at once, and after what pause (desktop spec section 12.3). */
private const val MaxDeferredRetries = 3
private const val DeferredRetryMs = 5_000L

/** The prefix of the temporary sync state of a download, kept apart until the library is published. */
private const val DownloadStatePrefix = ".download-"

/** What a requested run does, decided before it starts. */
internal sealed interface RunPlan {
    /** Synchronisation is paused; a run the application started by itself does nothing. */
    data object Paused : RunPlan

    /** Runs wait for an unmetered network, and the network is metered; one is asked for. */
    data object WaitForUnmetered : RunPlan

    /**
     * The run goes on.
     *
     * @param deferAttachments Whether attachments new or changed on the server wait for an unmetered network.
     */
    data class Run(val deferAttachments: Boolean) : RunPlan
}

/**
 * Decides what a requested run does. A run the person asked for always runs, also while paused
 * and on a metered network; there it moves only text unless the library synchronises everything.
 *
 * @param full Whether the person asked for the run (Sync now, Retry, pull to refresh, a new connection).
 * @param metered Whether the network is metered; no network counts as metered.
 * @param now The current time, which tells whether a pause has ended.
 */
internal fun planRun(settings: SyncSettings, full: Boolean, metered: Boolean, now: Instant): RunPlan = when {
    !full && settings.pausedAt(now) -> RunPlan.Paused
    !full && metered && settings.metered == MeteredSync.Off -> RunPlan.WaitForUnmetered
    else -> RunPlan.Run(deferAttachments = metered && settings.metered != MeteredSync.All)
}

/**
 * Returns the pause before a run after network failures in a row: 1, 2, 4 and more minutes, never
 * longer than the background interval, or [MaxRetryMinutes] without one.
 *
 * @param failures The failures in a row, at least 1.
 * @param interval The background interval in minutes; 0 is Off.
 */
internal fun retryDelayMs(failures: Int, interval: Int): Long =
    minOf(1L shl minOf(failures - 1, 10), (interval.takeIf { it > 0 } ?: MaxRetryMinutes).toLong()) * 60_000

/**
 * Returns the title of a conflict copy in the interface language: the note's title, or Untitled,
 * and the time (desktop spec section 3.7).
 */
fun Context.conflictTitle(title: String): String {
    val localized = inAppLanguage()
    val time = LocalDateTime.now().format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(localized.resources.configuration.locales[0]),
    )
    return localized.getString(R.string.sync_conflict_title, title.ifEmpty { localized.getString(R.string.note_untitled) }, time)
}

/**
 * SyncManager synchronises the open library with its sync storage: at start and on return to the
 * foreground, 30 seconds after a change, on pull to refresh and Sync now, and at the chosen
 * interval in the background. Runs go through [SyncWorker], one at a time; a request during a run
 * starts one more run when it ends. Network failures are retried after growing pauses; sign-in and
 * host key errors wait for the person. While synchronisation is paused, or the network is metered
 * and runs wait for an unmetered one, only the runs the person asks for go on; on a metered network
 * a run moves attachments as the library's choice says.
 *
 * It also checks and saves connections, downloads libraries, browses remote folders and
 * disconnects libraries.
 *
 * @param context The application context.
 * @param preferences The device preferences holding each library's sync settings.
 * @param session The libraries of this device.
 * @param scope The application's scope, for timers and the status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManager(
    private val context: Context,
    private val preferences: DevicePreferences,
    private val session: LibrarySession,
    private val scope: CoroutineScope,
) {
    private val credentials = Credentials(File(context.noBackupFilesDir, "credentials"))
    private val knownHosts = File(context.noBackupFilesDir, "known_hosts").toPath()
    private val state = MutableStateFlow(SyncStatus())
    private val conflictEvents = MutableSharedFlow<SyncConflicts>(extraBufferCapacity = 1)

    /** What synchronisation of the open library is doing. */
    val status: StateFlow<SyncStatus> = state.asStateFlow()

    /** Conflict copies made by runs, to announce. */
    val conflicts: SharedFlow<SyncConflicts> = conflictEvents.asSharedFlow()

    /** One run, download or disconnection at a time. */
    private val runLock = Mutex()

    @Volatile private var pending = false
    @Volatile private var pendingFull = false

    /** The fingerprint of a host key the person accepted, for the next run to trust. */
    @Volatile private var trust: String? = null

    /** Whether the last error needs the person (sign-in, host key) before runs go on by themselves. */
    @Volatile private var waitsForPerson = false

    /** Network failures in a row, for the growing pause before the next try; changed by runs only. */
    @Volatile private var failures = 0

    /**
     * Runs in a row that left changed files for later, each followed by a short retry up to
     * [MaxDeferredRetries]; counted by runs, reset by Sync now.
     */
    @Volatile private var deferredRuns = 0

    /**
     * Guards the timers below: the settings collector, the change collector, the worker and the
     * screens replace them from their own threads, and a timer replaced unseen would never be
     * cancelled.
     */
    private val timers = Any()
    private var retryJob: Job? = null
    private var changeJob: Job? = null

    /** Asks for a run when a pause with an end ends while the application runs. */
    private var pauseJob: Job? = null

    /** Whether an activity of the application is visible, which makes a failure the person sees in the application. */
    @Volatile var visible = false

    /** A key generated on the setup screen, kept here so it survives rotation without being saved anywhere. */
    var draftKey: SshKey? = null

    init {
        scope.launch {
            var shown: Pair<String?, SftpConnection?>? = null
            var scheduled: Triple<String?, Int, Boolean>? = null
            var pause: Pair<String?, Instant?>? = null
            preferences.openLibrary.flatMapLatest { id ->
                if (id == null) flowOf(null to null) else preferences.syncSettings(id).map { id to it }
            }.collect { (id, settings) ->
                val previous = shown
                shown = id to settings?.connection
                state.update { current ->
                    if (current.libraryId == id) current.copy(settings = settings)
                    else SyncStatus(libraryId = id, settings = settings, log = id?.let(::readLog).orEmpty())
                }
                // Rescheduling restarts the period, so only a change of when to run reschedules.
                val schedule = Triple(id, settings?.interval ?: 0, settings?.metered == MeteredSync.Off)
                if (schedule != scheduled) {
                    scheduled = schedule
                    SyncWorker.schedule(context, settings)
                }
                val paused = id to settings?.pausedUntil
                if (paused != pause) {
                    // Resuming by hand synchronises at once; a pause that ends by itself does when it ends.
                    if (pause?.first == id && pause?.second != null && settings?.pausedAt(now()) == false) request()
                    pause = paused
                    val until = settings?.pausedUntil
                    synchronized(timers) {
                        pauseJob?.cancel()
                        pauseJob = if (settings == null || until == null || until == PausedUntilResumed || !settings.pausedAt(now())) null
                        else scope.launch {
                            delay(Duration.between(now(), until).toMillis())
                            request()
                        }
                    }
                }
                // A library opened or connected now synchronises at once.
                if (settings != null && previous != shown) {
                    waitsForPerson = false
                    request()
                }
            }
        }
        scope.launch { session.localChanges.collect { afterChange() } }
    }

    private fun statePath(id: String) = File(context.noBackupFilesDir, "sync/$id/state.sqlite").toPath()

    /** Reads the sync log of a library; an unreadable state has no log. */
    private fun readLog(id: String): List<SyncRun> =
        if (!statePath(id).toFile().exists()) emptyList() else runCatching { SyncState.open(statePath(id)).use { it.runs() } }.getOrDefault(emptyList())

    /**
     * Asks for a run; it starts as soon as the worker gets to it.
     *
     * @param full Whether the person asked for it (Sync now, Retry, pull to refresh, a new
     *   connection): it lists every remote folder and runs also while paused or on a metered network.
     */
    private fun request(full: Boolean = false) {
        if (state.value.settings == null) return
        pending = true
        pendingFull = pendingFull || full
        SyncWorker.runSoon(context)
    }

    /** Synchronises as the application starts or returns to the foreground, unless an error waits for the person. */
    fun onForeground() {
        if (!waitsForPerson) request()
    }

    /** Starts a run that lists every remote folder (Sync now, Retry, pull to refresh), also after an error that waits for the person. */
    fun syncNow() {
        waitsForPerson = false
        deferredRuns = 0
        request(full = true)
    }

    /** Trusts a changed or unknown host key the person accepted after seeing its fingerprint, and synchronises. */
    fun trustHostKey(fingerprint: String) {
        trust = fingerprint
        syncNow()
    }

    /** Starts a run 30 seconds after the last local change. */
    private fun afterChange() {
        if (waitsForPerson || state.value.settings == null) return
        synchronized(timers) {
            changeJob?.cancel()
            changeJob = scope.launch {
                delay(ChangeDelayMs)
                request()
            }
        }
    }

    /** Asks for a run after a pause, unless the person must act first. */
    private fun retryAfter(ms: Long) {
        synchronized(timers) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(ms)
                if (!waitsForPerson) request()
            }
        }
    }

    /**
     * Runs the requested runs one after another, as [SyncWorker] does. A background run always
     * runs: it is the interval's.
     *
     * @param background Whether the interval started the run rather than the application.
     */
    suspend fun runPending(background: Boolean) = runLock.withLock {
        if (background) pending = true
        while (pending) {
            pending = false
            val full = pendingFull
            pendingFull = false
            runOnce(full)
        }
    }

    /**
     * Synchronises the open library once and records the outcome. A run the application started by
     * itself does nothing while synchronisation is paused, and on a metered network when runs wait
     * for an unmetered one, which is then asked for.
     *
     * @param full Whether the person asked for the run; see [request].
     */
    private suspend fun runOnce(full: Boolean) {
        val id = preferences.openLibrary.first() ?: return
        val settings = preferences.syncSettings(id).first() ?: return
        // No network counts as metered: a run waiting for an unmetered one also waits for any.
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
        val deferAttachments = when (val plan = planRun(settings, full, metered, now())) {
            RunPlan.Paused -> return
            RunPlan.WaitForUnmetered -> {
                SyncWorker.runWhenUnmetered(context)
                return
            }
            is RunPlan.Run -> plan.deferAttachments
        }
        synchronized(timers) { retryJob?.cancel() }
        state.update { it.copy(phase = SyncPhase.Running, done = 0, total = 0) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val secret = credentials.read(id) ?: throw FormatException(ErrorCode.SignIn, id, "no stored secret")
                val trusted = trust.also { trust = null }
                SftpStorage.open(settings.connection, secret, knownHosts, trusted).use { storage ->
                    SyncState.open(statePath(id)).use { syncState ->
                        synchronise(session.libraryFolder(id).toPath(), storage, syncState, syncOptions(full, deferAttachments))
                    }
                }
            }
        }
        val report = outcome.getOrNull()
        if (report != null) succeeded(id, report) else failed(id, outcome.exceptionOrNull()!!)
    }

    /**
     * Records a finished run: the log, the library read again when files changed, and conflicts
     * announced. A run that left locally changed files is partial: it keeps the time of the last
     * full success, shows the deferred state and is repeated a few times shortly after.
     */
    private suspend fun succeeded(id: String, report: SyncReport) {
        failures = 0
        waitsForPerson = false
        val conflicts = report.noteConflicts.size + report.fileConflicts.size
        val outcome = ErrorCode.Deferred.takeIf { report.skipped.isNotEmpty() }
        log(id, SyncRun(now(), report.uploaded, report.downloaded, report.merged, report.deleted, conflicts, outcome))
        if (outcome == null) {
            deferredRuns = 0
            preferences.updateSyncSettings(id) { it.copy(lastSynced = now()) }
        } else if (++deferredRuns <= MaxDeferredRetries) {
            retryAfter(DeferredRetryMs)
        }
        if (report.downloaded + report.merged + report.deleted + report.noteConflicts.size > 0) session.reloadAfterSync(id)
        if (report.deferredAttachments > 0) SyncWorker.runWhenUnmetered(context)
        SyncNotifications.cancelProblem(context)
        state.update {
            it.copy(phase = if (outcome == null) SyncPhase.Idle else SyncPhase.Failed, deferredAttachments = report.deferredAttachments, error = outcome, hostKey = null)
        }
        if (conflicts > 0) conflictEvents.tryEmit(SyncConflicts(conflicts, report.noteConflicts.firstOrNull()))
    }

    /** Records a failed run and decides when the next one starts. */
    private suspend fun failed(id: String, error: Throwable) {
        val code = (error as? FormatException)?.code ?: ErrorCode.Io
        val hostKey = (error as? HostKeyException)?.hostKey
        if (code == ErrorCode.Locked) {
            state.update { it.copy(phase = SyncPhase.Waiting, error = code) }
            retryAfter(LockedRetryMs)
            return
        }
        log(id, SyncRun(now(), error = code))
        state.update { it.copy(phase = SyncPhase.Failed, error = code, hostKey = hostKey) }
        when (code) {
            ErrorCode.Network -> {
                failures++
                retryAfter(retryDelayMs(failures, state.value.settings?.interval ?: 0))
            }
            ErrorCode.SignIn, ErrorCode.HostKey -> {
                waitsForPerson = true
                // A failure the person must fix is told outside the application only when it is not open.
                if (!visible && !SyncNotifications.showProblem(context, code, hostKey)) preferences.setAskNotifications(true)
            }
            else -> {}
        }
    }

    /** Adds a run to the library's log and shows the log; an unreadable state has no log. */
    private fun log(id: String, run: SyncRun) {
        val log = runCatching { SyncState.open(statePath(id)).use { it.addRun(run); it.runs() } }.getOrDefault(emptyList())
        state.update { if (it.libraryId == id) it.copy(log = log) else it }
    }

    /**
     * Returns the options of a run or a download, which shows its progress in the status and
     * titles conflict copies in the interface language.
     *
     * @param full Whether every remote folder is listed (Sync now).
     * @param deferAttachments Whether attachments new or changed on the server wait for an unmetered network.
     */
    private fun syncOptions(full: Boolean, deferAttachments: Boolean = false) = SyncOptions(
        fullListing = full,
        deferAttachmentDownloads = deferAttachments,
        progress = { done, total -> state.update { it.copy(done = done, total = total) } },
        conflictTitle = context::conflictTitle,
    )


    /**
     * Opens a connection the way a check does: connect, check the host key, sign in.
     *
     * @param secret The secret to sign in with; null signs in with the open library's stored one.
     * @throws FormatException With the code of the step that failed; [HostKeyException] for a key to confirm.
     */
    private suspend fun open(connection: SftpConnection, secret: SftpSecret?, trust: String?): SftpStorage {
        val signIn = secret ?: preferences.openLibrary.first()?.let(credentials::read) ?: throw FormatException(ErrorCode.SignIn, connection.host, "no stored secret")
        return SftpStorage.open(connection, signIn, knownHosts, trust)
    }

    /**
     * Checks a connection for the open library and saves it: connect, verify the host key, sign in,
     * write and remove a probe file, and check that the folder holds this library or nothing
     * (desktop spec section 12.6). A failure saves nothing.
     *
     * @param settings The connection with the preferences to keep.
     * @param secret The password or the key; null keeps the stored key.
     * @param trust The fingerprint of a host key the person accepted, or null.
     * @throws FormatException With the code of the step that failed.
     */
    suspend fun connect(settings: SyncSettings, secret: SftpSecret?, trust: String?) = runLock.withLock {
        val id = preferences.openLibrary.first() ?: throw FormatException(ErrorCode.NotFound, "library")
        withContext(Dispatchers.IO) {
            open(settings.connection, secret, trust).use { storage ->
                storage.probe()
                checkStorageFor(storage, id)
            }
            val previous = preferences.syncSettings(id).first()?.connection
            // Another folder is a first connection: what the state remembers belongs to the old one.
            val sameFolder = previous != null && previous.host == settings.connection.host &&
                previous.port == settings.connection.port && previous.folder == settings.connection.folder
            if (!sameFolder) File(context.noBackupFilesDir, "sync/$id").deleteRecursively()
            secret?.let { credentials.write(id, it) }
            preferences.setSyncSettings(id, settings)
        }
        draftKey = null
        waitsForPerson = false
        SyncNotifications.cancelProblem(context)
        state.update { it.copy(phase = SyncPhase.Idle, error = null, hostKey = null) }
        request(full = true)
    }

    /**
     * Downloads the library a remote folder holds into this device and opens it, already
     * connected. The connection is checked as by [connect] first.
     *
     * @throws FormatException With the code of the step that failed: NotFound when the folder holds
     *   no library, NotEmpty when its library is already on this device.
     */
    suspend fun download(settings: SyncSettings, secret: SftpSecret?, trust: String?) = runLock.withLock {
        if (secret == null) throw FormatException(ErrorCode.SignIn, settings.connection.host, "no secret")
        withContext(Dispatchers.IO) {
            open(settings.connection, secret, trust).use { storage ->
                storage.probe()
                val id = remoteLibraryId(storage)
                val target = session.libraryFolder(id)
                // An empty folder is free to publish into; one holding anything is never replaced.
                if (target.exists() && target.list()?.isEmpty() != true) throw FormatException(ErrorCode.NotEmpty, id, "the library is already on this device")
                val syncRoot = File(context.noBackupFilesDir, "sync")
                // What an earlier attempt the system stopped left; downloads run one at a time.
                syncRoot.listFiles { file -> file.name.startsWith(DownloadStatePrefix) }?.forEach { it.deleteRecursively() }
                val folder = session.downloadFolder()
                val stagedState = File(syncRoot, "$DownloadStatePrefix${System.nanoTime()}")
                val syncFolder = File(syncRoot, id)
                var published = false
                try {
                    state.update { it.copy(phase = SyncPhase.Running, done = 0, total = 0) }
                    SyncState.open(File(stagedState, "state.sqlite").toPath()).use { syncState ->
                        val report = synchronise(folder.toPath(), storage, syncState, syncOptions(full = false))
                        // A partial download is not a library: it fails and can be tried again.
                        if (report.skipped.isNotEmpty()) throw FormatException(ErrorCode.Deferred, folder.toString(), "files changed during the download")
                        syncState.addRun(SyncRun(now(), report.uploaded, report.downloaded, report.merged, report.deleted))
                    }
                    // The library is not on this device, so a state of its id is left from an earlier one.
                    syncFolder.deleteRecursively()
                    Files.move(stagedState.toPath(), syncFolder.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    published = true
                    credentials.write(id, secret)
                    preferences.setSyncSettings(id, settings.copy(lastSynced = now()))
                    if (!session.adopt(folder, id)) throw FormatException(ErrorCode.Io, folder.toString(), "cannot open the downloaded library")
                } catch (e: Exception) {
                    // Only what this attempt made goes; a library that appeared meanwhile stays.
                    runCatching { removePath(folder.toPath()) }
                    stagedState.deleteRecursively()
                    if (published && !target.resolve("library.json").exists()) {
                        syncFolder.deleteRecursively()
                        credentials.remove(id)
                        preferences.setSyncSettings(id, null)
                    }
                    throw e
                } finally {
                    state.update { if (it.phase == SyncPhase.Running) it.copy(phase = SyncPhase.Idle) else it }
                }
            }
        }
        draftKey = null
    }

    /**
     * Signs in for Browse, through a connection to the server's root folder.
     *
     * @throws FormatException With the code of the step that failed.
     */
    suspend fun browse(connection: SftpConnection, secret: SftpSecret?, trust: String?): RemoteBrowser =
        withContext(Dispatchers.IO) { RemoteBrowser(open(connection.copy(folder = "/"), secret, trust)) }

    /** Disconnects the open library: its connection, credentials and sync state go; the files stay on both sides. */
    suspend fun disconnect() {
        preferences.openLibrary.first()?.let { forget(it) }
    }

    /** Removes a library's connection, credentials and sync state, after a run that uses them ends. */
    suspend fun forget(id: String) = runLock.withLock {
        synchronized(timers) {
            retryJob?.cancel()
            changeJob?.cancel()
        }
        SyncWorker.cancelUnmetered(context)
        withContext(Dispatchers.IO) {
            preferences.setSyncSettings(id, null)
            credentials.remove(id)
            File(context.noBackupFilesDir, "sync/$id").deleteRecursively()
        }
        waitsForPerson = false
        SyncNotifications.cancelProblem(context)
        state.update { if (it.libraryId == id) SyncStatus(libraryId = id) else it }
    }
}

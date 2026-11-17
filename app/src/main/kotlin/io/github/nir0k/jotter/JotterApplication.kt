package io.github.nir0k.jotter

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.MaintenanceWorker
import io.github.nir0k.jotter.data.settings.Appearance
import io.github.nir0k.jotter.data.settings.DevicePreferences
import io.github.nir0k.jotter.data.sync.SyncManager
import io.github.nir0k.jotter.data.sync.SyncNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.github.nir0k.jotter.data.JotterServices

private val Context.deviceStore by preferencesDataStore(name = "device")

/**
 * JotterApplication owns the application-wide services. The stored interface language is applied
 * when the first activity opens, since the stored value is the source of truth for the
 * per-application language; a process started only for background work does not read it.
 */
class JotterApplication : Application(), JotterServices {

    /** The device preferences. */
    override val preferences: DevicePreferences by lazy { DevicePreferences(deviceStore) }

    private val scope = CoroutineScope(SupervisorJob())

    /** The library open on this device, opened at start-up. */
    override val library: LibrarySession by lazy { LibrarySession(this, preferences, scope) }

    /** Synchronisation of the open library with its sync storage. */
    override val sync: SyncManager by lazy { SyncManager(this, preferences, library, scope) }

    /**
     * The appearance read when the first activity opens, so its first frame already wears the stored
     * theme. One small blocking read, which a process started only for synchronisation, maintenance
     * or the tile never makes.
     */
    val initialAppearance: Appearance by lazy { runBlocking { preferences.appearance.first() } }

    override fun onCreate() {
        super.onCreate()
        SyncNotifications.createChannels(this)
        library.start()
        MaintenanceWorker.schedule(this)
        // Created at start, so the interval's runs are scheduled for the open library.
        sync
    }
}

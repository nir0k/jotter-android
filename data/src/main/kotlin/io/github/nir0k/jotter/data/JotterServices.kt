package io.github.nir0k.jotter.data

import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.settings.DevicePreferences
import io.github.nir0k.jotter.data.sync.SyncManager

/**
 * JotterServices are the application-wide services that background work and notifications reach
 * through the application context, which implements them.
 */
interface JotterServices {
    /** The device preferences. */
    val preferences: DevicePreferences

    /** The library open on this device. */
    val library: LibrarySession

    /** Synchronisation of the open library with its sync storage. */
    val sync: SyncManager
}

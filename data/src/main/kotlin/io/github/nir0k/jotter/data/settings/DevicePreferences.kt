package io.github.nir0k.jotter.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SignInMethod
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import io.github.nir0k.jotter.designsystem.theme.ThemeMode

/**
 * Appearance holds the device's look preferences; the language is the platform's per-application
 * language (see [interfaceLanguage]).
 *
 * @param paletteId Stored palette id, or null for the default; resolved with the product palettes.
 * @param mode Light or dark choice.
 */
data class Appearance(
    val paletteId: String? = null,
    val mode: ThemeMode = ThemeMode.System,
)

/** The intervals of background synchronisation offered, in minutes; 0 is Off. */
val SyncIntervals = listOf(0, 15, 30, 60)

/** What runs do on a metered network, such as a mobile one. */
enum class MeteredSync {
    /** Everything synchronises. */
    All,

    /** Notes and the library's files synchronise; attachments new or changed on the server wait for an unmetered network. */
    TextOnly,

    /** Runs the application starts by itself wait for an unmetered network; Sync now still runs, moving only text. */
    Off,
}

/** The [SyncSettings.pausedUntil] of a pause that lasts until the person resumes synchronisation. */
val PausedUntilResumed: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)

/**
 * SyncSettings is a library's sync connection and when to synchronise, a device setting kept per
 * library. Secrets live apart, encrypted.
 *
 * @param publicKey The public key of the key sign-in, shown on the Sync tab of Settings; null for a password.
 * @param interval Minutes between background runs, one of [SyncIntervals].
 * @param metered What runs do on a metered network.
 * @param pausedUntil When a pause of the runs the application starts by itself ends, or null when
 *   synchronisation is not paused; [PausedUntilResumed] for a pause until the person resumes it.
 * @param lastSynced When the last run succeeded, or null before the first.
 */
data class SyncSettings(
    val connection: SftpConnection,
    val publicKey: String? = null,
    val interval: Int = 15,
    val metered: MeteredSync = MeteredSync.All,
    val pausedUntil: Instant? = null,
    val lastSynced: Instant? = null,
) {
    /** Tells whether synchronisation is paused at the given time; a pause that ended is not. */
    fun pausedAt(time: Instant): Boolean = pausedUntil?.isAfter(time) == true

    /** Writes the settings as a JSON text. */
    fun encode(): String = JsonObject(
        mapOf(
            "host" to JsonPrimitive(connection.host),
            "port" to JsonPrimitive(connection.port),
            "user" to JsonPrimitive(connection.user),
            "folder" to JsonPrimitive(connection.folder),
            "signIn" to JsonPrimitive(connection.signIn.name),
            "publicKey" to JsonPrimitive(publicKey),
            "interval" to JsonPrimitive(interval),
            "metered" to JsonPrimitive(metered.name),
            "pausedUntil" to JsonPrimitive(pausedUntil?.toEpochMilli()),
            "lastSynced" to JsonPrimitive(lastSynced?.toEpochMilli()),
        ),
    ).toString()

    companion object {
        /** Reads settings written by [encode]; null when the text does not hold them. */
        fun decode(text: String): SyncSettings? = runCatching {
            val json = Json.parseToJsonElement(text).jsonObject
            fun string(key: String) = json[key]?.jsonPrimitive?.takeIf { it.isString }?.content
            SyncSettings(
                SftpConnection(
                    host = string("host")!!,
                    port = json["port"]?.jsonPrimitive?.intOrNull ?: 22,
                    user = string("user")!!,
                    folder = string("folder").orEmpty(),
                    signIn = SignInMethod.valueOf(string("signIn")!!),
                ),
                publicKey = string("publicKey"),
                interval = json["interval"]?.jsonPrimitive?.intOrNull?.takeIf { it in SyncIntervals } ?: 15,
                metered = MeteredSync.entries.firstOrNull { it.name == string("metered") } ?: MeteredSync.All,
                pausedUntil = json["pausedUntil"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochMilli),
                lastSynced = json["lastSynced"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochMilli),
            )
        }.getOrNull()
    }
}

/**
 * DevicePreferences keeps the preferences of this device in DataStore (`files/datastore/`).
 * Library settings live in the library itself; the choice of the open library and each library's
 * sync connection and sync preferences are kept here.
 */
class DevicePreferences(private val store: DataStore<Preferences>) {

    /** The current appearance; unknown or missing stored values fall back to the defaults. */
    val appearance: Flow<Appearance> = store.data.map { prefs ->
        Appearance(
            paletteId = prefs[PaletteKey],
            mode = ThemeMode.entries.firstOrNull { it.name == prefs[ModeKey] } ?: ThemeMode.System,
        )
    }

    /** Stores the chosen palette by its stable id. */
    suspend fun setPalette(id: String) {
        store.edit { it[PaletteKey] = id }
    }

    /** Stores the light or dark choice. */
    suspend fun setMode(mode: ThemeMode) {
        store.edit { it[ModeKey] = mode.name }
    }

    /** The order of every note list, one device preference for all lists. */
    val noteSort: Flow<NoteSort> = store.data.map { prefs ->
        NoteSort(
            key = NoteSort.Key.entries.firstOrNull { it.name == prefs[SortKey] } ?: NoteSort.Key.Updated,
            descending = prefs[SortDescendingKey] ?: true,
        )
    }

    /** Stores the order of the note lists. */
    suspend fun setNoteSort(sort: NoteSort) {
        store.edit {
            it[SortKey] = sort.key.name
            it[SortDescendingKey] = sort.descending
        }
    }

    /**
     * Whether new notes get the device's position as a geotag; off by default, and turned on
     * only once the location permission is granted.
     */
    val addLocation: Flow<Boolean> = store.data.map { it[AddLocationKey] ?: false }

    /** Stores whether new notes get a geotag. */
    suspend fun setAddLocation(add: Boolean) {
        store.edit { it[AddLocationKey] = add }
    }

    /**
     * The collections whose view shows only their own notes, without those of their
     * descendants; remembered per collection.
     */
    val ownNotesOnly: Flow<Set<String>> = store.data.map { it[OwnNotesKey].orEmpty() }

    /** Remembers whether a collection's view shows only its own notes. */
    suspend fun setOwnNotesOnly(collection: String, own: Boolean) {
        store.edit { prefs ->
            val current = prefs[OwnNotesKey].orEmpty()
            prefs[OwnNotesKey] = if (own) current + collection else current - collection
        }
    }

    /** The identifier of the library open on this device, or null before one is created. */
    val openLibrary: Flow<String?> = store.data.map { it[OpenLibraryKey] }

    /** Remembers the library to open at the next start; null forgets it. */
    suspend fun setOpenLibrary(id: String?) {
        store.edit { if (id == null) it.remove(OpenLibraryKey) else it[OpenLibraryKey] = id }
    }

    /** The sync settings of a library, or null when it has no sync storage. */
    fun syncSettings(libraryId: String): Flow<SyncSettings?> =
        store.data.map { prefs -> prefs[syncKey(libraryId)]?.let(SyncSettings::decode) }

    /** Stores the sync settings of a library; null removes its connection. */
    suspend fun setSyncSettings(libraryId: String, settings: SyncSettings?) {
        store.edit { if (settings == null) it.remove(syncKey(libraryId)) else it[syncKey(libraryId)] = settings.encode() }
    }

    /** Changes the stored sync settings of a library, when it has them. */
    suspend fun updateSyncSettings(libraryId: String, change: (SyncSettings) -> SyncSettings) {
        store.edit { prefs -> prefs[syncKey(libraryId)]?.let(SyncSettings::decode)?.let { prefs[syncKey(libraryId)] = change(it).encode() } }
    }

    /**
     * Whether the application should ask for the notification permission when it opens next: it
     * has not asked yet, or a background run failed in a way the person must fix and could not say
     * so.
     */
    val askNotifications: Flow<Boolean> = store.data.map { it[AskNotificationsKey] ?: true }

    /** Stores whether to ask for the notification permission when the application opens. */
    suspend fun setAskNotifications(ask: Boolean) {
        store.edit { it[AskNotificationsKey] = ask }
    }

    private companion object {
        fun syncKey(libraryId: String) = stringPreferencesKey("sync_$libraryId")
        val AskNotificationsKey = booleanPreferencesKey("ask_notifications")
        val PaletteKey = stringPreferencesKey("palette")
        val ModeKey = stringPreferencesKey("theme_mode")
        val OpenLibraryKey = stringPreferencesKey("open_library")
        val SortKey = stringPreferencesKey("note_sort")
        val SortDescendingKey = booleanPreferencesKey("note_sort_descending")
        val AddLocationKey = booleanPreferencesKey("add_location")
        val OwnNotesKey = stringSetPreferencesKey("own_notes_only")
    }
}

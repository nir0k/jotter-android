package io.github.nir0k.jotter

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nir0k.jotter.data.sync.SyncNotifications
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * AppRequest is a request from outside the main screen to show something: a notification's to
 * open the Sync tab of Settings, or the capture screen's to open a note a link points to.
 *
 * @param serial Grows with every request, also across a recreated activity, so each is handled once.
 * @param noteId The note to open, or null for the Sync tab of Settings.
 */
data class AppRequest(val serial: Long, val noteId: String?)

/**
 * MainActivity hosts the application edge to edge. The interface language is the platform's
 * per-application language, which it keeps.
 */
class MainActivity : ComponentActivity() {
    private val requests = MutableStateFlow<AppRequest?>(null)

    companion object {
        /** The extra of an intent asking to open a note. */
        const val OpenNoteExtra = "io.github.nir0k.jotter.OPEN_NOTE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as JotterApplication
        // Read before the activity is set up, so the stored language applies to it.
        val appearance = app.initialAppearance
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteRequest(intent)
        setContent { JotterApp(app.preferences, app.library, app.sync, appearance, requests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteRequest(intent)
    }

    /** Remembers a request to open the Sync tab of Settings or a note; the time since boot numbers it. */
    private fun noteRequest(intent: Intent?) {
        val note = intent?.getStringExtra(OpenNoteExtra)
        if (note != null || intent?.getBooleanExtra(SyncNotifications.OpenSyncExtra, false) == true) {
            requests.value = AppRequest(SystemClock.elapsedRealtimeNanos(), note)
        }
    }

    /**
     * Scans the library again on every return to the foreground, so files changed meanwhile show,
     * and synchronises it.
     */
    override fun onStart() {
        super.onStart()
        val app = application as JotterApplication
        app.library.refresh()
        app.sync.visible = true
        app.sync.onForeground()
        // The language may have changed while the application was away.
        SyncNotifications.createChannels(this)
    }

    override fun onStop() {
        super.onStop()
        (application as JotterApplication).sync.visible = false
    }
}

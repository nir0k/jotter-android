package io.github.nir0k.jotter.capture

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * NewNoteTileService is the Quick Settings tile New note: a tap opens the capture screen with an
 * empty note, after unlocking a locked phone.
 */
class NewNoteTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        // The tile acts rather than switches, so it always shows as ready.
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openNewNote) else openNewNote()
    }

    /** Opens the capture screen and collapses the Quick Settings panel. */
    private fun openNewNote() {
        val intent = Intent(this, CaptureActivity::class.java).setAction(NewNoteAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }
}

package io.github.nir0k.jotter.capture

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks which shared files a captured note takes: any other application's, and of Jotter's own
 * provider only an attachment of the open library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CapturableTest {
    private val own = "io.github.nir0k.jotter.files"
    private val library = "01a1f000-0000-7000-8000-00000000e4e4"
    private val note = "01a1f000-0000-7000-8000-00000000a0a0"

    private fun capturable(address: String) = isCapturable(Uri.parse(address), own, library)

    @Test
    fun takesOtherApplicationsFilesAndTheOpenLibrarysAttachments() {
        assertTrue(capturable("content://com.example.files/shared/report.pdf"))
        assertTrue(capturable("content://$own/libraries/$library/attachments/$note/scan%20(2).pdf"))
    }

    /** Another library, Jotter's other folders and addresses that would leave the attachment folder are refused. */
    @Test
    fun refusesJottersOtherFiles() {
        val other = "01a1f000-0000-7000-8000-00000000b0b0"
        for (address in listOf(
            "content://$own/libraries/$other/attachments/$note/scan.pdf",
            "content://$own/libraries/$library/notes/$note.html",
            "content://$own/libraries/$library/library.json",
            "content://$own/camera/IMG_20261108_120000.jpg",
            "content://$own/export/Notes.zip",
            "content://$own/libraries/$library/attachments/$note/..%2F..%2F..%2F$other%2Flibrary.json",
            "content://$own/libraries/$library/attachments/../$note/scan.pdf",
        )) {
            assertFalse(address, capturable(address))
        }
    }
}

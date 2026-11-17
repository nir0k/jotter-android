package io.github.nir0k.jotter.data.sync

import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SignInMethod
import io.github.nir0k.jotter.data.settings.MeteredSync
import io.github.nir0k.jotter.data.settings.PausedUntilResumed
import io.github.nir0k.jotter.data.settings.SyncSettings
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks when a requested run goes on and what it moves, and the pauses before retries. */
class SyncPlanTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    private fun settings(metered: MeteredSync = MeteredSync.All, pausedUntil: Instant? = null) =
        SyncSettings(SftpConnection("notes.example.org", 22, "anna", "notes", SignInMethod.Password), metered = metered, pausedUntil = pausedUntil)

    /** A pause stops the runs the application starts by itself until it ends; the person's runs still go. */
    @Test
    fun pauseStopsOnlyTheApplicationsRuns() {
        for (until in listOf(now.plusSeconds(3600), PausedUntilResumed)) {
            assertEquals(RunPlan.Paused, planRun(settings(pausedUntil = until), full = false, metered = false, now))
            assertEquals(RunPlan.Run(deferAttachments = false), planRun(settings(pausedUntil = until), full = true, metered = false, now))
        }
        assertEquals(RunPlan.Run(deferAttachments = false), planRun(settings(pausedUntil = now), full = false, metered = false, now))
    }

    /**
     * On a metered network each choice does its own: Everything moves all, Text only leaves
     * attachments, Wait for Wi-Fi waits unless the person asked, whose run then moves only text.
     */
    @Test
    fun meteredNetworkFollowsTheLibrarysChoice() {
        assertEquals(RunPlan.Run(deferAttachments = false), planRun(settings(MeteredSync.All), full = false, metered = true, now))
        assertEquals(RunPlan.Run(deferAttachments = true), planRun(settings(MeteredSync.TextOnly), full = false, metered = true, now))
        assertEquals(RunPlan.WaitForUnmetered, planRun(settings(MeteredSync.Off), full = false, metered = true, now))
        assertEquals(RunPlan.Run(deferAttachments = true), planRun(settings(MeteredSync.Off), full = true, metered = true, now))
        for (choice in MeteredSync.entries) {
            assertEquals(RunPlan.Run(deferAttachments = false), planRun(settings(choice), full = false, metered = false, now))
        }
    }

    /** Network failures are retried after 1, 2, 4 and more minutes, up to the interval or 15 minutes without one. */
    @Test
    fun retriesWaitLongerUpToTheInterval() {
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L), (1..6).map { retryDelayMs(it, 0) / 60_000 })
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L), (1..7).map { retryDelayMs(it, 30) / 60_000 })
        assertEquals(60L, retryDelayMs(40, 60) / 60_000)
    }
}

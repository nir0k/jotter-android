package io.github.nir0k.jotter.feature.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.sftp.HostKey
import io.github.nir0k.jotter.core.sync.SyncRun
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * SyncProblem is the explanation of a failed run or check: a one-line title and what happened
 * and what to do.
 */
class SyncProblem(val title: String, val text: String)

/**
 * Explains why a run or a connection check failed, in the interface language.
 *
 * @param code The error.
 * @param hostKey The server's key for a host key error.
 * @param download Whether the failure is a download's, which gives NotFound and NotEmpty their
 *   download meaning: a folder without a library, a library already on the phone.
 * @param check Whether the failure is a connection check's on the setup screen rather than a run's.
 */
@Composable
fun syncProblem(code: ErrorCode, hostKey: HostKey? = null, download: Boolean = false, check: Boolean = false): SyncProblem {
    val (title, text) = when (code) {
        ErrorCode.Network -> R.string.sync_error_network_title to if (check) R.string.sync_check_network_text else R.string.sync_error_network_text
        ErrorCode.SignIn -> R.string.sync_error_sign_in_title to if (check) R.string.sync_check_sign_in_text else R.string.sync_error_sign_in_text
        ErrorCode.HostKey -> if (hostKey?.state == HostKey.State.Changed) {
            R.string.sync_error_host_key_changed_title to R.string.sync_error_host_key_changed_text
        } else {
            R.string.sync_error_host_key_title to R.string.sync_error_host_key_text
        }
        ErrorCode.Locked -> R.string.sync_error_locked_title to R.string.sync_error_locked_text
        ErrorCode.OtherLibrary -> R.string.sync_error_other_library_title to R.string.sync_error_other_library_text
        ErrorCode.NotEmpty -> if (download) {
            R.string.sync_error_already_here_title to R.string.sync_error_already_here_text
        } else {
            R.string.sync_error_not_empty_title to R.string.sync_error_not_empty_text
        }
        ErrorCode.NotFound -> if (download) {
            R.string.sync_error_no_library_title to R.string.sync_error_no_library_text
        } else {
            R.string.sync_error_folder_title to R.string.sync_error_folder_text
        }
        ErrorCode.NewerFormat -> R.string.sync_error_newer_title to R.string.sync_error_newer_text
        ErrorCode.Deferred -> R.string.sync_error_deferred_title to R.string.sync_error_deferred_text
        ErrorCode.Malformed -> R.string.sync_error_malformed_title to R.string.sync_error_malformed_text
        else -> R.string.sync_error_io_title to R.string.sync_error_io_text
    }
    return SyncProblem(stringResource(title), stringResource(text))
}

/** Formats the time of a run: the time for today, the date and time otherwise, in the interface language. */
@Composable
fun syncTime(time: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val local = time.atZone(ZoneId.systemDefault())
    val format = if (local.toLocalDate() == LocalDate.now()) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return local.format(format.withLocale(locale))
}

/** Summarises a run of the sync log: what it copied, merged and deleted and the conflict copies it made, or why it failed. */
@Composable
fun runSummary(run: SyncRun): String {
    run.error?.let { return syncProblem(it).title }
    if (run.uploaded + run.downloaded + run.merged + run.deleted + run.conflicts == 0) return stringResource(R.string.sync_log_nothing)
    val parts = listOfNotNull(
        run.uploaded.takeIf { it > 0 }?.let { stringResource(R.string.sync_log_uploaded, it) },
        run.downloaded.takeIf { it > 0 }?.let { stringResource(R.string.sync_log_downloaded, it) },
        run.merged.takeIf { it > 0 }?.let { stringResource(R.string.sync_log_merged, it) },
        run.deleted.takeIf { it > 0 }?.let { stringResource(R.string.sync_log_deleted, it) },
        run.conflicts.takeIf { it > 0 }?.let { stringResource(R.string.sync_log_conflicts, it) },
    )
    return parts.joinToString(stringResource(R.string.list_separator))
}

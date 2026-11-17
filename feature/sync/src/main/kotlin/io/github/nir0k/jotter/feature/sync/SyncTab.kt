package io.github.nir0k.jotter.feature.sync

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.sftp.HostKey
import io.github.nir0k.jotter.core.sftp.SignInMethod
import io.github.nir0k.jotter.data.settings.MeteredSync
import io.github.nir0k.jotter.data.settings.PausedUntilResumed
import io.github.nir0k.jotter.data.settings.SyncIntervals
import io.github.nir0k.jotter.data.sync.SyncPhase
import io.github.nir0k.jotter.data.sync.SyncStatus
import io.github.nir0k.jotter.ui.SectionColumn
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch
import io.github.nir0k.jotter.designsystem.R as DesignR
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceDialog
import io.github.nir0k.jotter.designsystem.components.ChoiceRow
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.DangerButton
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.InlineActionRow
import io.github.nir0k.jotter.designsystem.components.IntegrationDiagram
import io.github.nir0k.jotter.designsystem.components.IntegrationStatus
import io.github.nir0k.jotter.designsystem.components.NoticeKind
import io.github.nir0k.jotter.designsystem.components.NavigationRow
import io.github.nir0k.jotter.designsystem.components.NoticePanel
import io.github.nir0k.jotter.designsystem.components.ProgressButton
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.components.SyncLogRow
import io.github.nir0k.jotter.designsystem.components.ValueRow
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.ui.R as UiR

/** The connection diagram of the Sync tab and the setup screen: the application, the status and the SFTP server. */
@Composable
fun SyncDiagram(status: IntegrationStatus) =
    IntegrationDiagram(AppIcons.Note, AppIcons.Server, MaterialTheme.colorScheme.tertiary, status)

/** Returns the status mark of synchronisation; a pause is a warning. */
private fun markOf(status: SyncStatus, now: Instant): IntegrationStatus {
    val settings = status.settings
    return when {
        settings == null -> IntegrationStatus.NotConfigured
        status.phase == SyncPhase.Failed -> IntegrationStatus.Error
        status.phase == SyncPhase.Waiting || settings.pausedAt(now) -> IntegrationStatus.Warning
        else -> IntegrationStatus.Ok
    }
}

/** Offers text through the system's share sheet. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * PublicKeyBlock shows a public key to add to the server's `authorized_keys` in a framed block
 * across the full width; the private key is never shown. Its Share and Copy are
 * [PublicKeyActions] in the row of actions below it.
 */
@Composable
fun PublicKeyBlock(publicKey: String) {
    SelectionContainer(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, Shapes.Trigger)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Shapes.Trigger)
            .padding(Dimens.CardPadding),
    ) {
        Text(publicKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/**
 * PublicKeyActions are the Share and Copy icons of a shown public key, placed in a row of inline
 * actions after any others; Copy confirms in the snackbar.
 *
 * @param publicKey The public key as one `authorized_keys` line.
 * @param snackbar Where Copy confirms, or null for no confirmation.
 */
@Composable
fun PublicKeyActions(publicKey: String, snackbar: SnackbarHostState?) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.sync_key_copied)
    // In the colour of the inline actions beside them, as the design system's coordinate actions.
    val tint = MaterialTheme.colorScheme.primary
    IconAction(AppIcons.Share, stringResource(R.string.sync_key_share), { shareText(context, publicKey) }, iconSize = Dimens.IconSecondary, tint = tint)
    IconAction(AppIcons.Copy, stringResource(R.string.sync_key_copy), {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, publicKey)))
            snackbar?.showSnackbar(copied)
        }
    }, iconSize = Dimens.IconSecondary, tint = tint)
}

/**
 * SyncTab is the tab of Settings with the library's SFTP storage, built as the integrations
 * template's service screen: the last sync, the diagram with the status, a notice explaining an error
 * with its action, the connection's details with Change sign-in, Sync now, the log of the five most
 * recent runs, when to synchronise (the interval, metered networks and a pause), Download from SFTP
 * and Disconnect last. A pause and attachments left for Wi-Fi show as notices. Without a connection
 * it explains synchronisation and offers Connect and Download from SFTP.
 *
 * @param status What synchronisation of the open library is doing.
 * @param onSyncNow Starts a run now.
 * @param onSetup Opens the setup screen: Connect, or Change sign-in.
 * @param onTrust Trusts the server's new host key, by the fingerprint the person saw.
 * @param onInterval Stores the interval of background runs.
 * @param onMetered Stores what runs do on a metered network.
 * @param onPause Pauses synchronisation until the given time, [PausedUntilResumed] for good, or
 *   resumes it with null.
 * @param onDisconnect Disconnects the library after the confirmation.
 * @param onDownload Opens the setup screen that downloads another library.
 * @param snackbar Messages of the tab.
 * @param initialTrusting Whether the host key comparison starts open, for screenshots.
 */
@Composable
fun SyncTab(
    status: SyncStatus,
    onSyncNow: () -> Unit,
    onSetup: () -> Unit,
    onTrust: (String) -> Unit,
    onInterval: (Int) -> Unit,
    onMetered: (MeteredSync) -> Unit,
    onPause: (Instant?) -> Unit,
    onDisconnect: () -> Unit,
    onDownload: () -> Unit,
    snackbar: SnackbarHostState,
    initialTrusting: Boolean = false,
) {
    var disconnecting by remember { mutableStateOf(false) }
    var trusting by remember { mutableStateOf(initialTrusting) }
    val settings = status.settings
    // A pause that ends shows as ended with the next change of the status.
    val now = remember(status) { Instant.now() }
    SectionColumn {
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        settings == null -> stringResource(R.string.sync_not_connected)
                        else -> settings.lastSynced?.let { stringResource(R.string.sync_last, syncTime(it)) } ?: stringResource(R.string.sync_never)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncDiagram(markOf(status, now))
            }
        }
        if (settings == null) {
            item { NoticePanel(NoticeKind.Info, stringResource(R.string.sync_about_title), stringResource(R.string.sync_about_text)) }
            item { ProgressButton(stringResource(R.string.sync_connect), onSetup, Modifier.fillMaxWidth(), icon = AppIcons.Link) }
            item { SecondaryButton(stringResource(R.string.library_download), onDownload, Modifier.fillMaxWidth(), icon = AppIcons.Download) }
            return@SectionColumn
        }
        val error = status.error
        if (error != null && status.phase != SyncPhase.Running) {
            item {
                val problem = syncProblem(error, status.hostKey)
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap)) {
                    NoticePanel(if (status.phase == SyncPhase.Waiting) NoticeKind.Warning else NoticeKind.Error, problem.title, problem.text)
                    when {
                        error == ErrorCode.Locked -> {}
                        error == ErrorCode.SignIn -> ProgressButton(stringResource(R.string.sync_change), onSetup, Modifier.fillMaxWidth(), icon = AppIcons.Key)
                        error == ErrorCode.HostKey && status.hostKey != null ->
                            SecondaryButton(stringResource(R.string.sync_compare_keys), { trusting = true }, Modifier.fillMaxWidth(), icon = AppIcons.Security)
                        else -> SecondaryButton(stringResource(UiR.string.sync_retry), onSyncNow, Modifier.fillMaxWidth(), icon = AppIcons.Refresh)
                    }
                }
            }
        }
        val pausedUntil = settings.pausedUntil
        if (pausedUntil != null && settings.pausedAt(now)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap)) {
                    NoticePanel(
                        NoticeKind.Warning,
                        stringResource(R.string.sync_paused_title),
                        if (pausedUntil == PausedUntilResumed) stringResource(R.string.sync_paused_text)
                        else stringResource(R.string.sync_paused_until_text, syncTime(pausedUntil)),
                    )
                    SecondaryButton(stringResource(R.string.sync_resume), { onPause(null) }, Modifier.fillMaxWidth(), icon = AppIcons.Play)
                }
            }
        }
        if (status.deferredAttachments > 0 && status.phase != SyncPhase.Running) {
            item {
                NoticePanel(
                    NoticeKind.Info,
                    pluralStringResource(R.plurals.sync_deferred_title, status.deferredAttachments, status.deferredAttachments),
                    stringResource(R.string.sync_deferred_text),
                )
            }
        }
        item {
            SettingsSection(stringResource(R.string.sync_details)) {
                val connection = settings.connection
                ValueRow(stringResource(R.string.sync_server), "${connection.user}@${connection.host}" + if (connection.port == 22) "" else ":${connection.port}")
                ValueRow(stringResource(R.string.sync_folder), connection.folder.ifEmpty { stringResource(R.string.sync_folder_home) })
                ValueRow(
                    stringResource(R.string.sync_sign_in),
                    stringResource(if (connection.signIn == SignInMethod.Password) R.string.sync_sign_in_password else R.string.sync_sign_in_key),
                )
                settings.publicKey?.let {
                    PublicKeyBlock(it)
                    InlineActionRow { PublicKeyActions(it, snackbar) }
                }
                SecondaryButton(stringResource(R.string.sync_change), onSetup, Modifier.fillMaxWidth(), icon = AppIcons.Key)
            }
        }
        item {
            val running = status.phase == SyncPhase.Running
            ProgressButton(
                when {
                    !running -> stringResource(R.string.sync_now)
                    status.total > 0 -> stringResource(R.string.sync_running_progress, status.done, status.total)
                    else -> stringResource(R.string.sync_running)
                },
                onSyncNow,
                Modifier.fillMaxWidth(),
                busy = running,
                icon = AppIcons.Sync,
            )
        }
        item {
            SettingsSection(stringResource(R.string.sync_log)) {
                if (status.log.isEmpty()) {
                    Text(stringResource(R.string.sync_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (run in status.log) SyncLogRow(syncTime(run.finished), runSummary(run), run.error == null)
            }
        }
        item {
            SettingsSection(stringResource(R.string.sync_when)) {
                ChoiceRow(
                    stringResource(R.string.sync_interval),
                    SyncIntervals.map { Choice(it, if (it == 0) stringResource(R.string.sync_interval_off) else pluralStringResource(R.plurals.sync_interval_minutes, it, it)) },
                    settings.interval,
                    onInterval,
                    icon = AppIcons.Time,
                )
                ChoiceRow(
                    stringResource(R.string.sync_metered),
                    listOf(
                        Choice(MeteredSync.All, stringResource(R.string.sync_metered_all)),
                        Choice(MeteredSync.TextOnly, stringResource(R.string.sync_metered_text_only)),
                        Choice(MeteredSync.Off, stringResource(R.string.sync_metered_off)),
                    ),
                    settings.metered,
                    onMetered,
                    icon = AppIcons.Phone,
                )
                PauseRow(pausedUntil?.takeIf { settings.pausedAt(now) }, onPause)
            }
        }
        item { SecondaryButton(stringResource(R.string.library_download), onDownload, Modifier.fillMaxWidth(), icon = AppIcons.Download) }
        item { DangerButton(stringResource(R.string.sync_disconnect), { disconnecting = true }, Modifier.fillMaxWidth()) }
    }
    if (disconnecting) {
        ConfirmDialog(
            stringResource(R.string.sync_disconnect_title),
            stringResource(R.string.sync_disconnect_text),
            stringResource(R.string.sync_disconnect),
            onConfirm = {
                disconnecting = false
                onDisconnect()
            },
            onDismiss = { disconnecting = false },
            destructive = true,
        )
    }
    val hostKey = status.hostKey
    if (trusting && hostKey != null) {
        HostKeyDialog(hostKey, onTrust = {
            trusting = false
            onTrust(hostKey.fingerprint)
        }, onDismiss = { trusting = false })
    }
}

/** The choices of the pause dialog; Resume is offered only while paused. */
private enum class PauseChoice { Resume, Hour, Day, ThreeDays, Week, Date, Forever }

/**
 * PauseRow shows how long synchronisation is paused and opens the choice of a pause: for an hour,
 * one, three or seven days, until a date chosen in a calendar (the start of that day) or until the
 * person resumes it. The pause saves at once.
 *
 * @param pausedUntil The end of the pause in force, or null when not paused.
 * @param onPause Receives the new end of the pause, or null to resume.
 */
@Composable
private fun PauseRow(pausedUntil: Instant?, onPause: (Instant?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var dating by remember { mutableStateOf(false) }
    NavigationRow(
        stringResource(R.string.sync_pause),
        { choosing = true },
        icon = AppIcons.Pause,
        value = when (pausedUntil) {
            null -> stringResource(R.string.sync_pause_none)
            PausedUntilResumed -> stringResource(R.string.sync_pause_until_resumed)
            else -> stringResource(R.string.sync_pause_until, syncTime(pausedUntil))
        },
        trailing = AppIcons.ChevronDown,
    )
    if (choosing) {
        ChoiceDialog(
            stringResource(R.string.sync_pause_title),
            listOfNotNull(
                Choice(PauseChoice.Resume, stringResource(R.string.sync_resume), AppIcons.Play).takeIf { pausedUntil != null },
                Choice(PauseChoice.Hour, stringResource(R.string.sync_pause_hour)),
                Choice(PauseChoice.Day, pluralStringResource(R.plurals.sync_pause_days, 1, 1)),
                Choice(PauseChoice.ThreeDays, pluralStringResource(R.plurals.sync_pause_days, 3, 3)),
                Choice(PauseChoice.Week, pluralStringResource(R.plurals.sync_pause_days, 7, 7)),
                Choice(PauseChoice.Date, stringResource(R.string.sync_pause_date), AppIcons.Calendar),
                Choice(PauseChoice.Forever, stringResource(R.string.sync_pause_forever)),
            ),
            selected = null,
            onSelect = { choice ->
                val now = Instant.now()
                when (choice) {
                    PauseChoice.Resume -> onPause(null)
                    PauseChoice.Hour -> onPause(now.plus(Duration.ofHours(1)))
                    PauseChoice.Day -> onPause(now.plus(Duration.ofDays(1)))
                    PauseChoice.ThreeDays -> onPause(now.plus(Duration.ofDays(3)))
                    PauseChoice.Week -> onPause(now.plus(Duration.ofDays(7)))
                    PauseChoice.Date -> dating = true
                    PauseChoice.Forever -> onPause(PausedUntilResumed)
                }
            },
            onDismiss = { choosing = false },
        )
    }
    if (dating) PauseDateDialog({ day -> onPause(day.atStartOfDay(ZoneId.systemDefault()).toInstant()) }, { dating = false })
}

/**
 * PauseDateDialog chooses the day a pause ends with Material's date picker, as the design system
 * has no date control. Only days from tomorrow can be chosen.
 *
 * @param onApply Receives the chosen day.
 * @param onDismiss Closes the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseDateDialog(onApply: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The picker counts days as UTC midnights.
    fun Long.day() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.day().isAfter(today)
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (selected != null) onApply(selected.day())
            }, enabled = selected != null) { Text(stringResource(DesignR.string.ds_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.ds_cancel)) } },
    ) {
        DatePicker(state, title = { Text(stringResource(R.string.sync_pause_date_title), Modifier.padding(start = Dimens.DialogPadding, top = Dimens.DialogPadding)) })
    }
}

/**
 * HostKeyDialog shows a server's host key before the person trusts it: its type and SHA256
 * fingerprint, and for a changed key the fingerprint recorded before.
 */
@Composable
fun HostKeyDialog(hostKey: HostKey, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val changed = hostKey.state == HostKey.State.Changed
    ConfirmDialog(
        stringResource(if (changed) R.string.host_key_changed_title else R.string.host_key_unknown_title),
        stringResource(if (changed) R.string.host_key_changed_text else R.string.host_key_unknown_text),
        stringResource(R.string.host_key_trust),
        onConfirm = onTrust,
        onDismiss = onDismiss,
        destructive = changed,
        details = listOfNotNull(
            hostKey.knownFingerprint?.let { stringResource(R.string.host_key_recorded, it) },
            stringResource(if (changed) R.string.host_key_new else R.string.host_key_presented, hostKey.type, hostKey.fingerprint),
        ),
    )
}

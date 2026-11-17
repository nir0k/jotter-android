package io.github.nir0k.jotter.feature.sync

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.sftp.HostKey
import io.github.nir0k.jotter.core.sftp.HostKeyException
import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SftpSecret
import io.github.nir0k.jotter.core.sftp.SignInMethod
import io.github.nir0k.jotter.core.sftp.SshKey
import io.github.nir0k.jotter.core.sftp.generateKey
import io.github.nir0k.jotter.core.sftp.importKey
import io.github.nir0k.jotter.data.settings.SyncSettings
import io.github.nir0k.jotter.data.sync.FolderContents
import io.github.nir0k.jotter.data.sync.FolderListing
import io.github.nir0k.jotter.data.sync.RemoteBrowser
import io.github.nir0k.jotter.ui.SectionScreen
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.AppTextField
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.FormDialog
import io.github.nir0k.jotter.designsystem.components.InlineAction
import io.github.nir0k.jotter.designsystem.components.InlineActionRow
import io.github.nir0k.jotter.designsystem.components.InlineError
import io.github.nir0k.jotter.designsystem.components.IntegrationStatus
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.NavigationRow
import io.github.nir0k.jotter.designsystem.components.NoticeKind
import io.github.nir0k.jotter.designsystem.components.NoticePanel
import io.github.nir0k.jotter.designsystem.components.PasswordField
import io.github.nir0k.jotter.designsystem.components.ProgressButton
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.components.SegmentedChoice
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens

/** What the setup screen does with a connection that passes the check. */
enum class SetupMode {
    /** Connects the open library to the folder. */
    Connect,

    /** Downloads the folder's library to the phone and opens it. */
    Download,
}

/** A private key file is read only up to this size; key files are a few kilobytes. */
private const val MaxKeyFileBytes = 64 * 1024

/** Reads a key file up to [MaxKeyFileBytes]; a longer file is no key and fails to parse. */
private fun readKeyFile(input: InputStream): ByteArray {
    val buffer = ByteArray(MaxKeyFileBytes)
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count < 0) break
        read += count
    }
    return buffer.copyOf(read)
}

/** The comment of a key generated on the phone, telling the server's owner where it comes from. */
private const val GeneratedKeyComment = "jotter-android"

/**
 * SyncSetupScreen is the integrations template's setup screen for the SFTP storage: the host, port,
 * user name and remote folder with Browse, and the sign-in with a password or a key, generated on
 * the phone or imported. Check and save first checks the connection; a failure saves nothing, keeps
 * the fields and names the step that failed. Secrets are never prefilled; a connection being
 * changed keeps its stored key until another is chosen.
 *
 * @param mode Whether the check connects the open library or downloads a library.
 * @param current The connection being changed, whose non-secret values fill the fields, or null.
 * @param draftKey A key generated or imported on this screen and not saved yet.
 * @param onDraftKey Keeps a generated or imported key across rotation.
 * @param progress Files done and to do while a library downloads, or null.
 * @param onCheck Checks and saves the connection: the settings, the secret (null keeps the stored
 *   key) and the fingerprint of a host key the person accepted; throws [FormatException] naming
 *   the failed step.
 * @param onBrowse Signs in for Browse, as [onCheck] does without the probe.
 * @param onDone Leaves the screen after a successful check.
 * @param initialError A failed check to start with, for screenshots.
 */
@Composable
fun SyncSetupScreen(
    mode: SetupMode,
    current: SyncSettings?,
    draftKey: SshKey?,
    onDraftKey: (SshKey?) -> Unit,
    progress: Pair<Int, Int>?,
    onCheck: suspend (SyncSettings, SftpSecret?, String?) -> Unit,
    onBrowse: suspend (SftpConnection, SftpSecret?, String?) -> RemoteBrowser,
    onDone: () -> Unit,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    initialError: ErrorCode? = null,
) {
    val connection = current?.connection
    var host by rememberSaveable { mutableStateOf(connection?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((connection?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(connection?.user.orEmpty()) }
    var folder by rememberSaveable { mutableStateOf(connection?.folder.orEmpty()) }
    var method by rememberSaveable { mutableStateOf(connection?.signIn ?: SignInMethod.Password) }
    var password by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var keyFile by remember { mutableStateOf<ByteArray?>(null) }
    var keyError by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    var hostKey by remember { mutableStateOf<HostKey?>(null) }
    var afterTrust by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }
    var browser by remember { mutableStateOf<RemoteBrowser?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A key the stored connection signs in with stays until another is chosen.
    val keepsStoredKey = method == SignInMethod.Key && draftKey == null && connection?.signIn == SignInMethod.Key && mode == SetupMode.Connect
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = host.isNotBlank() && user.isNotBlank() && portNumber != null &&
        (if (method == SignInMethod.Password) password.isNotEmpty() else draftKey != null || keepsStoredKey)
    fun edited() {
        error = null
    }
    fun connectionOf() = SftpConnection(host.trim(), portNumber ?: 22, user.trim(), folder.trim(), method)
    fun secretOf(): SftpSecret? = if (method == SignInMethod.Password) SftpSecret.Password(password) else draftKey?.let(SftpSecret::Key)

    /** Runs a step that signs in; an unknown or changed host key asks the person first and runs it again when trusted. */
    fun attempt(step: suspend (String?) -> Unit) {
        scope.launch {
            checking = true
            error = null
            try {
                step(null)
            } catch (e: HostKeyException) {
                hostKey = e.hostKey
                afterTrust = step
            } catch (e: FormatException) {
                error = e.code
            } finally {
                checking = false
            }
        }
    }

    val check: suspend (String?) -> Unit = { trust ->
        val settings = (current ?: SyncSettings(connectionOf())).copy(
            connection = connectionOf(),
            publicKey = if (method == SignInMethod.Key) draftKey?.publicKey ?: current?.publicKey else null,
        )
        onCheck(settings, secretOf(), trust)
        onDone()
    }
    val browse: suspend (String?) -> Unit = { trust -> browser = onBrowse(connectionOf(), secretOf(), trust) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(::readKeyFile) }.getOrNull()
            }
            keyFile = data
            passphrase = ""
            keyError = null
            if (data == null) {
                keyError = R.string.sync_key_unreadable
                return@launch
            }
            try {
                onDraftKey(withContext(Dispatchers.Default) { importKey(data, null) })
                keyFile = null
            } catch (e: FormatException) {
                keyError = if (e.code == ErrorCode.SignIn) R.string.sync_key_needs_passphrase else R.string.sync_key_not_a_key
                if (e.code != ErrorCode.SignIn) keyFile = null
            }
        }
    }

    BackHandler(enabled = checking) {}
    val title = stringResource(if (mode == SetupMode.Download) R.string.sync_download_title else R.string.sync_setup_title)
    SectionScreen(title, { if (!checking) onBack() }, snackbar = snackbar) {
        // Keyed, so the notice of a failed check coming and going keeps the focused field's section.
        item("diagram") { SyncDiagram(IntegrationStatus.NotConfigured) }
        if (mode == SetupMode.Download) {
            item("intro") { Text(stringResource(R.string.sync_download_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        error?.let { code ->
            item("error") {
                val problem = syncProblem(code, download = mode == SetupMode.Download, check = true)
                NoticePanel(NoticeKind.Error, stringResource(R.string.sync_check_failed, problem.title), problem.text)
            }
        }
        item("server") {
            SettingsSection(stringResource(R.string.sync_section_server)) {
                AppTextField(host, { host = it; edited() }, stringResource(R.string.sync_host), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                AppTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5); edited() },
                    stringResource(R.string.sync_port),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    error = if (port.isNotEmpty() && portNumber == null) stringResource(R.string.sync_port_invalid) else null,
                )
                AppTextField(user, { user = it; edited() }, stringResource(R.string.sync_user))
                AppTextField(
                    folder,
                    { folder = it; edited() },
                    stringResource(R.string.sync_folder),
                    supporting = stringResource(R.string.sync_folder_help),
                    error = if (error == ErrorCode.NotFound && mode == SetupMode.Connect) stringResource(R.string.sync_folder_missing) else null,
                )
                SecondaryButton(stringResource(R.string.sync_browse), { attempt(browse) }, Modifier.fillMaxWidth(), enabled = ready && !checking, icon = AppIcons.Folder)
            }
        }
        item("signIn") {
            // Read unconditionally: a resource read that comes and goes in the arguments below makes
            // Compose rebuild the key section, which drops the focus of the passphrase being typed.
            val keyErrorText = stringResource(keyError ?: R.string.sync_key_needs_passphrase)
            val signInRefused = stringResource(R.string.sync_sign_in_refused)
            SettingsSection(stringResource(R.string.sync_section_sign_in)) {
                SegmentedChoice(
                    null,
                    listOf(Choice(SignInMethod.Password, stringResource(R.string.sync_sign_in_password)), Choice(SignInMethod.Key, stringResource(R.string.sync_sign_in_key))),
                    method,
                    { method = it; edited() },
                )
                if (method == SignInMethod.Password) {
                    PasswordField(
                        password,
                        { password = it; edited() },
                        stringResource(R.string.sync_password),
                        error = if (error == ErrorCode.SignIn) signInRefused else null,
                    )
                } else {
                    KeySection(
                        draftKey = draftKey,
                        storedKey = current?.publicKey?.takeIf { keepsStoredKey },
                        keyFile = keyFile,
                        passphrase = passphrase,
                        onPassphrase = { passphrase = it; keyError = null },
                        error = if (keyError != null) keyErrorText else if (error == ErrorCode.SignIn) signInRefused else null,
                        onGenerate = {
                            scope.launch {
                                onDraftKey(withContext(Dispatchers.Default) { generateKey(GeneratedKeyComment) })
                                keyFile = null
                                keyError = null
                                edited()
                            }
                        },
                        onImport = { importer.launch(arrayOf("*/*")) },
                        onUsePassphrase = {
                            val data = keyFile ?: return@KeySection
                            scope.launch {
                                try {
                                    onDraftKey(withContext(Dispatchers.Default) { importKey(data, passphrase) })
                                    keyFile = null
                                    keyError = null
                                    edited()
                                } catch (_: FormatException) {
                                    keyError = R.string.sync_key_wrong_passphrase
                                }
                            }
                        },
                        snackbar = snackbar,
                    )
                }
            }
        }
        item("save") {
            ProgressButton(
                when {
                    progress != null && progress.second > 0 -> stringResource(R.string.sync_downloading_progress, progress.first, progress.second)
                    checking && mode == SetupMode.Download -> stringResource(R.string.sync_downloading)
                    checking -> stringResource(R.string.sync_checking)
                    else -> stringResource(R.string.sync_check_save)
                },
                { attempt(check) },
                Modifier.fillMaxWidth(),
                busy = checking,
                enabled = ready,
                icon = AppIcons.Check,
            )
        }
    }
    hostKey?.let { key ->
        HostKeyDialog(
            key,
            onTrust = {
                hostKey = null
                val step = afterTrust ?: return@HostKeyDialog
                afterTrust = null
                attempt { step(key.fingerprint) }
            },
            onDismiss = {
                hostKey = null
                afterTrust = null
            },
        )
    }
    browser?.let { open ->
        BrowseDialog(
            open,
            start = folder.trim(),
            onChoose = {
                folder = it
                edited()
                browser = null
                scope.launch(Dispatchers.IO) { open.close() }
            },
            onDismiss = {
                browser = null
                scope.launch(Dispatchers.IO) { open.close() }
            },
        )
    }
}

/**
 * KeySection is the key sign-in: the public key to add to the server, the passphrase of an imported
 * key that has one, and one row of Generate key, Import key and the shown key's Share and Copy.
 *
 * @param draftKey The key generated or imported on the screen, or null.
 * @param storedKey The public key the stored connection signs in with, while it is kept.
 * @param keyFile An imported key file waiting for its passphrase, or null.
 */
@Composable
private fun KeySection(
    draftKey: SshKey?,
    storedKey: String?,
    keyFile: ByteArray?,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    error: String?,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onUsePassphrase: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val shown = draftKey?.publicKey ?: storedKey
    if (shown != null) {
        Text(stringResource(R.string.sync_key_add), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PublicKeyBlock(shown)
    } else {
        Text(stringResource(R.string.sync_key_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (keyFile != null) {
        PasswordField(passphrase, onPassphrase, stringResource(R.string.sync_passphrase), error = error)
        SecondaryButton(stringResource(R.string.sync_key_use), onUsePassphrase, Modifier.fillMaxWidth(), enabled = passphrase.isNotEmpty())
    } else if (error != null) {
        InlineError(error)
    }
    InlineActionRow {
        InlineAction(AppIcons.Key, stringResource(R.string.sync_key_generate), onGenerate)
        InlineAction(AppIcons.Upload, stringResource(R.string.sync_key_import), onImport)
        shown?.let { PublicKeyActions(it, snackbar) }
    }
}

/**
 * BrowseDialog lists the folders on the server: the shown folder's path and whether it holds a
 * Jotter library, is empty or holds other files, its subfolders and the way up, New folder, and
 * Choose, which puts the shown folder's path into the folder field.
 *
 * @param browser The signed-in connection.
 * @param start The folder typed in; empty for the home folder.
 */
@Composable
private fun BrowseDialog(browser: RemoteBrowser, start: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var failed by remember { mutableStateOf<ErrorCode?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<ErrorCode?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        failed = null
        try {
            listing = browser.list(target)
        } catch (e: FormatException) {
            // A folder typed in that is missing starts the browsing at the home folder.
            if (target.isNotEmpty() && listing == null && e.code == ErrorCode.NotFound) target = "" else failed = e.code
        }
    }
    val shown = listing
    FormDialog(
        stringResource(R.string.sync_browse_title),
        stringResource(R.string.sync_browse_choose),
        onConfirm = { shown?.let { onChoose(it.path) } },
        onDismiss = onDismiss,
        error = failed?.let { syncProblem(it).title },
        confirmEnabled = shown != null,
    ) {
        if (shown == null) {
            if (failed == null) LoadingState()
            return@FormDialog
        }
        Text(shown.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        Text(
            stringResource(
                when (shown.contents) {
                    FolderContents.Library -> R.string.sync_browse_library
                    FolderContents.Empty -> R.string.sync_browse_empty
                    FolderContents.Other -> R.string.sync_browse_other
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.heightIn(max = Dimens.ChoiceListMaxHeight).verticalScroll(rememberScrollState())) {
            if (shown.path != "/") {
                NavigationRow(stringResource(R.string.sync_browse_up), { target = shown.path.substringBeforeLast('/').ifEmpty { "/" } }, icon = AppIcons.ArrowUp)
            }
            for (child in shown.folders) {
                NavigationRow(child, { target = "${shown.path.trimEnd('/')}/$child" }, icon = AppIcons.Folder)
            }
        }
        InlineAction(AppIcons.Add, stringResource(R.string.sync_browse_new), {
            name = ""
            nameError = null
            naming = true
        })
    }
    if (naming && shown != null) {
        val valid = name.isNotBlank() && '/' !in name && name.trim() != "." && name.trim() != ".."
        FormDialog(
            stringResource(R.string.sync_browse_new),
            stringResource(R.string.sync_browse_create),
            onConfirm = {
                scope.launch {
                    try {
                        target = browser.create(shown.path, name.trim())
                        naming = false
                    } catch (e: FormatException) {
                        nameError = e.code
                    }
                }
            },
            onDismiss = { naming = false },
            error = nameError?.let { syncProblem(it).title },
            confirmEnabled = valid,
        ) {
            AppTextField(name, { name = it; nameError = null }, stringResource(R.string.sync_browse_name))
        }
    }
}

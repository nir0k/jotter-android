package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/**
 * AppTextField is the outlined field with a floating label used in every form. Single-line
 * fields have fully rounded ends, multi-line fields a 20 dp corner. Supporting text shows help,
 * or the error when [error] is set; the typed value is kept after a rejected save.
 *
 * @param value Current text.
 * @param onValueChange Receives edits.
 * @param label Localized field label.
 * @param singleLine Whether the field is one line.
 * @param minLines Minimum visible lines of a multi-line field.
 * @param supporting Optional help text.
 * @param error Optional error text; replaces the help text and marks the field.
 * @param placeholder Optional text shown while the field is empty and focused.
 * @param keyboardOptions Keyboard type and IME action for the field's data.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = error != null,
        shape = if (singleLine) Shapes.Field else Shapes.TextArea,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * PasswordField is a masked field with a show/hide toggle. Stored secrets are never loaded into
 * it; an empty field showing [placeholder] ("Configured") means "keep the stored value".
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    placeholder: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        supporting = supporting,
        error = error,
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) AppIcons.Hide else AppIcons.Show,
                    stringResource(if (visible) R.string.ds_hide_password else R.string.ds_show_password),
                    modifier = Modifier.size(Dimens.IconSecondary),
                )
            }
        },
    )
}

/**
 * FormDialog is the full-width dialog for creating or editing a small object. Fields scroll
 * above the keyboard while Cancel and the confirm action stay visible. While [busy] the dialog
 * cannot be dismissed and the confirm action shows progress; a refusal is shown in [error]
 * and the fields keep their values.
 *
 * @param title Localized dialog title.
 * @param confirmText Localized confirm label ("Save", "Create").
 * @param busy Whether the operation is running.
 * @param error Localized failure of the last attempt, or null.
 * @param onConfirm Starts the operation; the caller closes the dialog on success.
 * @param onDismiss Closes the dialog without saving.
 * @param confirmEnabled Whether the fields are valid.
 * @param destructive Whether confirming deletes something (account deletion with a password).
 * @param content The fields, laid out with 12 dp gaps.
 */
@Composable
fun FormDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(Dimens.DialogMargin).imePadding(),
            shape = Shapes.Dialog,
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.padding(Dimens.DialogPadding), verticalArrangement = Arrangement.spacedBy(Dimens.DialogFieldGap)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.DialogFieldGap),
                ) { content() }
                error?.let { InlineError(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ds_cancel)) }
                    ProgressButton(confirmText, onConfirm, busy = busy, enabled = confirmEnabled, destructive = destructive)
                }
            }
        }
    }
}

/**
 * ConfirmDialog asks before an action that cannot be undone. The text names the consequence
 * and, for group actions, the exact count captured when the dialog opened.
 *
 * @param title Localized question ("Delete 3 photos?").
 * @param text Localized consequence.
 * @param confirmText Localized action label, repeated from the initiating control.
 * @param destructive Whether the action deletes; the confirm label uses the error colour.
 * @param details Optional bullet lines (what else is removed).
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    details: List<String> = emptyList(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = Shapes.Dialog,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text)
                details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

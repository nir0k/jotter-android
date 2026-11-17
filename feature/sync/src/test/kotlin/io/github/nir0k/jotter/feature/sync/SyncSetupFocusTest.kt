package io.github.nir0k.jotter.feature.sync

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.sftp.SshKey
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Checks that a field of the SFTP setup keeps its focus while an error goes with the first typed
 * character: the passphrase's own error once rebuilt the key section, and the notice of a failed
 * check once shifted the sections below it, both moving the focus away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w400dp-h2000dp")
class SyncSetupFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    /** Typing after the "protected key" message and after a wrong passphrase leaves the field focused. */
    @Test
    fun passphraseKeepsFocusWhileItsErrorGoes() {
        // ssh-keygen makes a key protected by the passphrase "secret" for the run, so the repository
        // holds no private key, not even a test one.
        val keyFile = File(context.cacheDir, "protected-key").apply { delete() }
        val made = runCatching {
            ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "secret", "-C", "", "-f", keyFile.path).start().waitFor()
        }.getOrNull()
        assumeTrue("Needs ssh-keygen", made == 0)
        // The document picker answers with the key file at once.
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(keyFile)))
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                var draftKey by remember { mutableStateOf<SshKey?>(null) }
                SyncSetupScreen(
                    SetupMode.Download, null, draftKey, { draftKey = it }, null,
                    { _, _, _ -> }, { _, _, _ -> error("no server") }, {}, {}, remember { SnackbarHostState() },
                )
            }
        }
        val passphrase = compose.onNodeWithText(text(R.string.sync_passphrase))

        compose.onNodeWithText(text(R.string.sync_sign_in_key)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.sync_key_import)).performScrollTo().performClick()
        waitForText(R.string.sync_key_needs_passphrase)
        passphrase.performScrollTo().performClick()
        passphrase.performTextInput("w")
        passphrase.assertIsFocused()

        passphrase.performTextInput("rong")
        compose.onNodeWithText(text(R.string.sync_key_use)).performScrollTo().performClick()
        waitForText(R.string.sync_key_wrong_passphrase)
        passphrase.performScrollTo().performClick()
        passphrase.performTextInput("x")
        passphrase.assertIsFocused()
    }

    /** Typing after a failed check removes its notice above the fields; the field being typed keeps its focus. */
    @Test
    fun fieldKeepsFocusWhenTheFailedCheckGoes() {
        compose.setContent {
            SyncSetupScreen(
                SetupMode.Download, null, null, {}, null,
                { _, _, _ -> }, { _, _, _ -> error("no server") }, {}, {}, remember { SnackbarHostState() },
                initialError = ErrorCode.Network,
            )
        }
        val host = compose.onNodeWithText(text(R.string.sync_host))
        host.performClick()
        host.performTextInput("n")
        host.assertIsFocused()
    }

    /** The English text of a string resource. */
    private fun text(id: Int) = context.getString(id)

    /** Waits until the text of a resource shows; the key is read off the main thread. */
    private fun waitForText(id: Int) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text(id))).fetchSemanticsNodes().isNotEmpty() }
}

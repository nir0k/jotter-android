package io.github.nir0k.jotter.data.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.sftp.HostKey
import kotlinx.coroutines.flow.first
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import io.github.nir0k.jotter.data.JotterServices
import io.github.nir0k.jotter.designsystem.theme.isDark
import io.github.nir0k.jotter.data.R
import io.github.nir0k.jotter.data.settings.inAppLanguage

/**
 * SyncNotifications shows the two kinds of sync notifications: the ongoing notification of a long
 * run, on the low-importance Synchronisation channel, and the sign-in or host key error of a
 * background run, on the Sync problems channel. Both follow the design system's rules: the
 * palette's primary colour as the accent, a monochrome glyph as the small icon, the text in the
 * application's language; tapping one opens the Sync tab of Settings.
 */
object SyncNotifications {
    /** The identifier of the ongoing sync notification. */
    const val OngoingId = 1001
    private const val ProblemId = 1002
    private const val SyncChannel = "sync"
    private const val ProblemChannel = "sync_problems"

    /** The extra of the intent a notification opens the application with, asking for the Sync tab of Settings. */
    const val OpenSyncExtra = "io.github.nir0k.jotter.OPEN_SYNC"

    /** Creates the channels, or renames them after a change of the application's language. */
    fun createChannels(context: Context) {
        val text = context.inAppLanguage()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SyncChannel, text.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ProblemChannel, text.getString(R.string.channel_sync_problems), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /**
     * Returns the primary colour of the palette the application wears, the accent of the
     * notifications; a run reads it once for all updates of its ongoing notification.
     */
    suspend fun accent(context: Context): Int {
        val appearance = (context.applicationContext as JotterServices).preferences.appearance.first()
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return ThemePalette.resolve(appearance.paletteId).variant(appearance.mode.isDark(systemDark)).colors.primary.toArgb()
    }

    /** Returns the intent that opens the Sync tab of Settings. */
    private fun openSync(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        // The application's own launcher activity, which opens the Sync tab on this extra.
        context.packageManager.getLaunchIntentForPackage(context.packageName)!!.putExtra(OpenSyncExtra, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Posts a notification when the application may: notifications are on and the permission is
     * granted.
     *
     * @return Whether it was posted.
     */
    private fun post(context: Context, id: Int, notification: Notification): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { manager.notify(id, notification) }.isSuccess
    }

    /**
     * Builds the ongoing notification of a long run, with its progress when it is known.
     *
     * @param done Files done.
     * @param total Files to do; 0 while they are being listed.
     * @param accent The notification's colour, from [accent].
     */
    fun ongoing(context: Context, done: Int, total: Int, accent: Int): Notification {
        val text = context.inAppLanguage()
        return NotificationCompat.Builder(context, SyncChannel)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setColor(accent)
            .setContentTitle(text.getString(R.string.notification_syncing))
            .apply { if (total > 0) setContentText(text.getString(R.string.sync_progress, done, total)) }
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openSync(context))
            .build()
    }

    /** Updates the ongoing notification with the progress of the run, when it shows. */
    fun updateOngoing(context: Context, done: Int, total: Int, accent: Int) {
        post(context, OngoingId, ongoing(context, done, total, accent))
    }

    /**
     * Removes the ongoing notification when a run ends. Android ignores this while WorkManager
     * still holds it for its foreground service, which removes it itself when the run stops.
     */
    fun cancelOngoing(context: Context) {
        NotificationManagerCompat.from(context).cancel(OngoingId)
    }

    /**
     * Shows the one notification of a background run that failed in a way the person must fix: a
     * refused sign-in or an unknown or changed host key.
     *
     * @return Whether it could be shown; without the permission it waits until the application
     *   opens and asks for it.
     */
    suspend fun showProblem(context: Context, code: ErrorCode, hostKey: HostKey?): Boolean {
        val text = context.inAppLanguage()
        val message = when {
            code == ErrorCode.SignIn -> R.string.notification_problem_sign_in
            hostKey?.state == HostKey.State.Changed -> R.string.notification_problem_host_key_changed
            else -> R.string.notification_problem_host_key
        }
        val notification = NotificationCompat.Builder(context, ProblemChannel)
            .setSmallIcon(R.drawable.ic_notification_sync)
            .setColor(accent(context))
            .setContentTitle(text.getString(R.string.notification_problem_title))
            .setContentText(text.getString(message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.getString(message)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openSync(context))
            .build()
        return post(context, ProblemId, notification)
    }

    /** Removes the problem notification once the problem is solved. */
    fun cancelProblem(context: Context) {
        NotificationManagerCompat.from(context).cancel(ProblemId)
    }
}

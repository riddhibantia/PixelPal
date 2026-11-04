package com.pixelpal.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pixelpal.app.R
import com.pixelpal.app.domain.model.Reminder
import com.pixelpal.app.domain.model.ReminderCategory
import com.pixelpal.app.domain.model.ReminderSound
import com.pixelpal.app.presentation.MainActivity
import com.pixelpal.app.receiver.ReminderActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Builds the reminder notification and resolves which sound a reminder makes.
 *
 * Sound resolution order (see [resolveSound]):
 *  1. an explicit silent marker in `soundUri` → genuinely silent,
 *  2. a user-picked ringtone URI → its ringtone type,
 *  3. otherwise the category's [ReminderCategory.soundHint].
 *
 * `soundUri == null` keeps its LEGACY meaning of "system default" so rows
 * created before categories existed keep alerting.
 *
 * On API 26+ the notification channel owns the sound, so silent reminders are
 * routed through a dedicated silent channel ([Constants.CHANNEL_REMINDER_SILENT]).
 */
@Singleton
class ReminderNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun show(reminder: Reminder) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(manager)

        val pendingIntent = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completePendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = Constants.ACTION_COMPLETE_REMINDER
                putExtra("reminder_id", reminder.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = Constants.ACTION_SNOOZE_REMINDER
                putExtra("reminder_id", reminder.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val category = ReminderCategory.fromId(reminder.category)
        val silent = resolveSound(reminder) == ReminderSound.NONE
        val channelId = if (silent) ensureSilentChannel(manager) else Constants.CHANNEL_REMINDER

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle(reminder.title)
            .setContentText(reminder.message?.takeIf { it.isNotBlank() } ?: "It's time!")
            .setSubText(category.displayName)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(0, "Complete", completePendingIntent)
            .addAction(0, "Snooze 10 min", snoozePendingIntent)

        if (!silent) {
            // Vibration lives on the notification too so it also works on API 26+
            // where the channel pattern is fixed at creation time.
            builder.setVibrate(longArrayOf(0, 400, 200, 400))
        }

        manager.notify(Constants.NOTIFICATION_ID_REMINDER + reminder.id.toInt(), builder.build())
    }

    fun cancel(reminderId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(Constants.NOTIFICATION_ID_REMINDER + reminderId.toInt())
    }

    /**
     * Logical sound class for [reminder]. See the class KDoc for precedence.
     */
    fun resolveSound(reminder: Reminder): ReminderSound {
        if (ReminderSound.isSilentUri(reminder.soundUri)) return ReminderSound.NONE
        val pickedUri = reminder.soundUri?.takeIf { it.isNotBlank() }
        if (pickedUri != null) return soundForType(ringtoneTypeOf(pickedUri))
        return ReminderCategory.fromId(reminder.category).soundHint
    }

    /** Ringtone for a resolved [sound]; `null` means "play nothing". */
    fun ringtoneUriFor(sound: ReminderSound): Uri? = when (sound) {
        ReminderSound.NONE -> null
        ReminderSound.DEFAULT, ReminderSound.ALARM ->
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ReminderSound.NOTIFICATION, ReminderSound.CHIME ->
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /**
     * URI the alarm pipeline should play: a user-picked ringtone as-is, the
     * category-resolved default, or `null` when the reminder is silent.
     */
    fun playableRingtoneUri(reminder: Reminder): Uri? {
        val picked = reminder.soundUri?.takeIf { it.isNotBlank() && !ReminderSound.isSilentUri(it) }
        return if (picked != null) Uri.parse(picked) else ringtoneUriFor(resolveSound(reminder))
    }

    /** Only the default remote (or the picker) can carry sound in this app. */
    private fun soundForType(type: Int): ReminderSound = when (type) {
        RingtoneManager.TYPE_NOTIFICATION -> ReminderSound.NOTIFICATION
        RingtoneManager.TYPE_RINGTONE -> ReminderSound.CHIME
        RingtoneManager.TYPE_ALARM -> ReminderSound.ALARM
        else -> ReminderSound.DEFAULT
    }

    /**
     * Best-effort classification of a stored URI: the system's default alarm /
     * notification URIs map exactly; anything else (a content:// pick) is kept
     * audio-producing by treating it as an alarm.
     */
    private fun ringtoneTypeOf(uri: String): Int = try {
        when (uri) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString() ->
                RingtoneManager.TYPE_NOTIFICATION
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString() ->
                RingtoneManager.TYPE_RINGTONE
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString() ->
                RingtoneManager.TYPE_ALARM
            else -> RingtoneManager.TYPE_ALARM
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not classify reminder ringtone URI, defaulting to alarm")
        RingtoneManager.TYPE_ALARM
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_REMINDER,
                Constants.CHANNEL_REMINDER_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** Silent sibling of [Constants.CHANNEL_REMINDER] — no sound, no vibration. */
    private fun ensureSilentChannel(manager: NotificationManager): String {
        val id = Constants.CHANNEL_REMINDER_SILENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                id,
                Constants.CHANNEL_REMINDER_SILENT_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = "Reminders you chose to keep silent"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
        return id
    }
}

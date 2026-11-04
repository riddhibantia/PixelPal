package com.pixelpal.app.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The reminder categories persisted as [Reminder.category]. Kept as a sealed
 * catalogue (not an enum) so stored values stay backward compatible with the
 * legacy free-string column ("CUSTOM" default).
 */
enum class ReminderCategory(
    val id: String,
    val displayName: String
) {
    MEETING("MEETING", "Meeting"),
    MEDICINE("MEDICINE", "Medicine"),
    BIRTHDAY("BIRTHDAY", "Birthday"),
    SHOPPING("SHOPPING", "Shopping"),
    ASSIGNMENT("ASSIGNMENT", "Assignment"),
    CUSTOM("CUSTOM", "Custom");

    val icon: ImageVector
        get() = when (this) {
            MEETING -> Icons.Default.Call
            MEDICINE -> Icons.Default.Medication
            BIRTHDAY -> Icons.Default.Cake
            SHOPPING -> Icons.Default.ShoppingCart
            ASSIGNMENT -> Icons.Default.Edit
            CUSTOM -> Icons.Default.Notes
        }

    /** Optional SFX hint used by the reminder notification builder. */
    val soundHint: ReminderSound
        get() = when (this) {
            MEETING, ASSIGNMENT -> ReminderSound.NOTIFICATION
            MEDICINE -> ReminderSound.ALARM
            BIRTHDAY -> ReminderSound.CHIME
            SHOPPING -> ReminderSound.NONE
            CUSTOM -> ReminderSound.DEFAULT
        }

    companion object {
        val ALL: List<ReminderCategory> = entries

        /** Unknown/legacy strings fall back to [CUSTOM] — never crash on data. */
        fun fromId(id: String?): ReminderCategory =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * Logical sound class for a reminder, resolved to an actual ringtone at
 * delivery time. [NONE] is genuinely silent (the old `soundUri = null` meant
 * "system default", which made "Silent" impossible).
 */
enum class ReminderSound(val id: String, val displayName: String) {
    DEFAULT("DEFAULT", "Default"),
    ALARM("ALARM", "Alarm"),
    NOTIFICATION("NOTIFICATION", "Notification"),
    CHIME("CHIME", "Chime"),
    NONE("NONE", "Silent");

    companion object {
        val ALL: List<ReminderSound> = entries
        fun fromId(id: String?): ReminderSound =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT

        /**
         * Token persisted in `Reminder.soundUri` when the user explicitly picks
         * [NONE]. Legacy rows store `null` there, which means "system default" —
         * that is why silence needs its own marker instead of `null`.
         * A bare "silent" (the string some legacy pickers wrote) is also honoured.
         */
        const val SILENT_URI_TOKEN = "pixelpal://sound/none"
        private const val LEGACY_SILENT_TOKEN = "silent"

        /** True when the stored URI means "make no sound" rather than "system default". */
        fun isSilentUri(uri: String?): Boolean {
            val normalized = uri?.trim()?.lowercase() ?: return false
            return normalized == SILENT_URI_TOKEN || normalized == LEGACY_SILENT_TOKEN
        }
    }
}

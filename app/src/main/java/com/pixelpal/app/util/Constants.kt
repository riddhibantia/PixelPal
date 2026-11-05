package com.pixelpal.app.util

object Constants {
    const val APP_NAME = "PixelPal"
    const val PACKAGE_NAME = "com.pixelpal.app"
    const val DEFAULT_PET_TYPE = "cat"

    // DataStore keys
    const val PREFERENCES_NAME = "pixelpal_preferences"
    const val KEY_OVERLAY_X = "overlay_x"
    const val KEY_OVERLAY_Y = "overlay_y"
    const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    const val KEY_SELECTED_PET_TYPE = "selected_pet_type"
    const val KEY_PET_NAME = "pet_name"
    const val KEY_USER_NAME = "user_name"
    const val KEY_USER_EMAIL = "user_email"
    const val KEY_AVATAR_SEED = "avatar_seed"
    const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
    const val KEY_CURRENT_THEME = "current_theme"
    const val KEY_ACTIVE_COMPANION_ID = "active_companion_id"
    const val KEY_COMPANION_BOOTSTRAP_DONE = "companion_bootstrap_done"
    const val KEY_SINGLE_COMPANION_FOLD_DONE = "single_companion_fold_done"
    const val KEY_TASKS_WIDGET_ENABLED = "tasks_widget_enabled"
    const val KEY_GEMINI_API_KEY_OVERRIDE = "gemini_api_key_override"

    /** pixelpal-bridge pairing: base URL and shared key (see tools/agent-bridge). */
    const val KEY_AGENT_BRIDGE_URL = "agent_bridge_url"
    const val KEY_AGENT_BRIDGE_KEY = "agent_bridge_key"
    const val KEY_AGENT_BRIDGE_ID = "agent_bridge_id"

    /** Bridge approvals already surfaced as notifications ("<companionId>|<approvalId>"). */
    const val KEY_NOTIFIED_BRIDGE_APPROVALS = "notified_bridge_approvals"

    // Notification channels
    const val CHANNEL_COMPANION = "companion_channel"
    const val CHANNEL_REMINDER = "reminder_channel"
    /** Silent sibling of [CHANNEL_REMINDER] — no sound, no vibration. */
    const val CHANNEL_REMINDER_SILENT = "reminder_channel_silent"
    const val CHANNEL_AGENT = "agent_channel"
    /** Task due-date notification channel (TaskDueWorker). */
    const val CHANNEL_TASK_DUE = "task_due_channel"
    const val CHANNEL_COMPANION_NAME = "Companion"
    const val CHANNEL_REMINDER_NAME = "Reminders"
    const val CHANNEL_REMINDER_SILENT_NAME = "Reminders (silent)"
    const val CHANNEL_AGENT_NAME = "AI Agents"
    const val CHANNEL_TASK_DUE_NAME = "Task Due Dates"

    // Overlay defaults
    const val OVERLAY_SIZE_DP = 64f
    const val OVERLAY_OFFSET_X_DP = 80f
    const val OVERLAY_OFFSET_Y_DP = 200f
    const val OVERLAY_ISLAND_TOP_DP = 6f
    const val KEYBOARD_DODGE_THRESHOLD_DP = 100f

    // Default values
    const val DEFAULT_OVERLAY_ENABLED = true
    const val DEFAULT_SELECTED_PET_TYPE = "cat"
    const val DEFAULT_PET_NAME = "PixelPal"
    const val DEFAULT_USER_NAME = ""
    const val DEFAULT_USER_EMAIL = ""
    const val DEFAULT_AVATAR_SEED = "pixelpal"
    const val DEFAULT_IS_FIRST_LAUNCH = true
    const val DEFAULT_CURRENT_THEME = "dark"

    // Foreground service
    const val FOREGROUND_SERVICE_ID = 1001
    const val NOTIFICATION_ID_COMPANION = 1002
    const val NOTIFICATION_ID_REMINDER = 1003
    const val NOTIFICATION_ID_AGENT = 1004

    /** Aggregated "you missed N reminders" notification (BootCatchUpWorker). */
    const val NOTIFICATION_ID_MISSED_REMINDERS = 1005

    /**
     * Base id for "Task due" notifications (TaskDueWorker). Added to the task's
     * Room row id, so the id stays stable per task and never collides with the
     * reminder (1003+id) or agent (1004+…) ranges.
     */
    const val NOTIFICATION_ID_TASK_DUE = 1100

    // Companion limits & agent polling
    const val MAX_BOND_LEVEL = 100
    const val MAX_SIMULTANEOUS_OVERLAYS = 1

    /** Daily taps that still grant bond before interactions become reaction-only. */
    const val BOND_GRANTING_TAPS_PER_DAY = 3
    const val DEFAULT_AGENT_POLL_INTERVAL_MIN = 15L
    const val AGENT_WORK_PREFIX = "agent_poll_"
    /** Unique WorkManager name prefix for per-task due notifications. */
    const val TASK_DUE_WORK_PREFIX = "task_due_"
    /** Polling interval choices offered in the workspace (WorkManager floor is 15 min). */
    val AGENT_POLL_INTERVAL_OPTIONS_MIN = listOf(15L, 30L, 60L, 120L)

    // Notification action intents
    const val ACTION_COMPLETE_REMINDER = "com.pixelpal.app.action.COMPLETE_REMINDER"
    const val ACTION_SNOOZE_REMINDER = "com.pixelpal.app.action.SNOOZE_REMINDER"
    const val ACTION_APPROVE_AGENT = "com.pixelpal.app.action.APPROVE_AGENT"
    const val ACTION_DENY_AGENT = "com.pixelpal.app.action.DENY_AGENT"
    const val KEY_LAST_APPROVAL_ID = "last_approval_id"

    // Room database
    const val DATABASE_NAME = "pixelpal_database_v2"
}

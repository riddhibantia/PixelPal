package com.pixelpal.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pixelpal.app.data.local.datastore.PreferencesManager
import com.pixelpal.app.overlay.OverlayService
import com.pixelpal.app.worker.BootCatchUpWorker
import com.pixelpal.app.worker.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.d("BootReceiver received BOOT_COMPLETED")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Alarms do not survive a reboot. re-arm future ones, roll
                // recurring ones forward, and get back the past-due one-shots
                // that cannot be armed anymore (they would fire instantly).
                val missed = try {
                    reminderScheduler.rescheduleAll()
                } catch (e: Exception) {
                    Timber.e(e, "BootReceiver: rescheduleAll failed")
                    emptyList()
                }

                // Persist the catch-up in WorkManager (survives this short
                // goAsync window): marks the missed one-shots TRIGGERED,
                // records the activity event and posts one aggregated
                // "you missed N reminders" notification.
                if (missed.isNotEmpty()) {
                    BootCatchUpWorker.enqueue(context, missed.map { it.id })
                }

                try {
                    val enabled = preferencesManager.overlayEnabled.first()
                    if (enabled) {
                        OverlayService.start(context)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "BootReceiver: overlay restore failed")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

package com.pixelpal.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pixelpal.app.data.local.datastore.CompanionBootstrapInitializer
import com.pixelpal.app.domain.engine.ActiveCompanionManager
import com.pixelpal.app.domain.repository.AgentConnectionRepository
import com.pixelpal.app.overlay.OverlayManager
import com.pixelpal.app.worker.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class PixelPalApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var companionBootstrapInitializer: CompanionBootstrapInitializer

    @Inject
    lateinit var activeCompanionManager: ActiveCompanionManager

    @Inject
    lateinit var agentConnectionRepository: AgentConnectionRepository

    @Inject
    lateinit var syncCoordinator: com.pixelpal.app.data.remote.firebase.FirestoreSyncCoordinator

    @Inject
    lateinit var overlayManager: OverlayManager

    @Inject
    lateinit var agentBridgePoller: com.pixelpal.app.data.remote.bridge.AgentBridgePoller

    @Inject
    lateinit var preferencesManager: com.pixelpal.app.data.local.datastore.PreferencesManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        installCrashLogger()

        // Foreground tracking for the overlay: the floating bubble parks
        // itself while the user is inside the app (it would otherwise run a
        // second infinite Lottie loop on top of the in-app hero).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // Track visibility, not RESUMED. During an in-app activity
            // transition Android pauses the outgoing Activity before the new
            // one resumes; a resumed counter briefly reaches zero there and
            // can let the overlay service re-add its touchable window over
            // PixelPal's UI (including the bottom navigation).
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) overlayManager.setAppForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) overlayManager.setAppForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        workerScheduler.schedulePeriodicWork()

        // Real-time cloud → local listeners (no-op until signed in).
        syncCoordinator.startRealtimeSync()

        // Startup reconciliation: one-time fold of legacy multi-companion data
        // into THE companion, fresh-install seeding, bond/personality guarantees,
        // and restore of any configured agent polling.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                companionBootstrapInitializer.runStartupReconciliation()
                // Returning session (Firebase persists auth across restarts):
                // pull users/{uid} BEFORE the UI reads Room, or Home renders
                // the just-seeded default instead of the real cloud companion.
                // Fresh-seed rows always lose to cloud rows (see coordinator),
                // so this never clobbers a genuinely new user (no cloud yet).
                if (syncCoordinator.isUserLoggedIn) {
                    try {
                        syncCoordinator.onUserAuthenticated()
                    } catch (e: Exception) {
                        Timber.w(e, "Cold-start cloud restore failed")
                    }
                }
                activeCompanionManager.ensureValidActiveCompanion()
                // Restore polling for the companion's agent when configured.
                agentConnectionRepository.getPollingEnabledDirect().forEach { connection ->
                    workerScheduler.scheduleAgentPolling(
                        connection.companionId,
                        connection.pollingIntervalMinutes
                    )
                }
                // Multi-agent bridge: keep sessions live in this process and
                // schedule the worker fallback for when it is gone.
                if (preferencesManager.bridgePaired.first()) {
                    agentBridgePoller.start()
                    workerScheduler.scheduleBridgePolling()
                }
            } catch (e: Exception) {
                Timber.e(e, "Companion bootstrap failed")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Crash visibility (release builds included)
    // ---------------------------------------------------------------------
    //
    // The app ships no remote crash-reporting SDK (no Crashlytics/Analytics,
    // see README "Privacy"), so release crashes used to be completely
    // invisible: the process died and the stack trace was lost. This installs
    // a process-wide UncaughtExceptionHandler that writes the throwable to a
    // rotating log in filesDir before delegating to the platform handler (so
    // the process still dies exactly as before — no swallowed crashes), and
    // exposes it through CrashLog (readable from the UI).
    //
    // Kept in this file on purpose: it must be armed before anything else in
    // the process can throw, i.e. in Application.onCreate().

    /** One recorded fatal crash. Serialized to JSON by [CrashLog]. */
    @Serializable
    data class CrashRecord(
        val timestamp: Long,
        val versionName: String,
        val versionCode: Int,
        val thread: String,
        val message: String,
        val stackTrace: String
    )

    /**
     * File-backed crash store: `filesDir/crashes/crash_log.jsonl` (append-only,
     * one JSON object per line, capped at [MAX_ENTRIES] newest records).
     * Reads/writes happen on a dedicated single thread so a write can never
     * block the main thread from inside an uncaught-exception handler.
     */
    object CrashLog {
        private const val DIR = "crashes"
        private const val FILE = "crash_log.jsonl"
        private const val MAX_ENTRIES = 20

        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pixelpal-crash-log").apply { isDaemon = true }
        }

        private val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun logFile(dir: File): File = File(File(dir, DIR), FILE)

        /** Appends one crash record. Synchronous — called from the dying process. */
        fun record(dir: File, record: CrashRecord) {
            runCatching {
                val file = logFile(dir)
                file.parentFile?.mkdirs()
                val fresh = file.exists() && file.length() > MAX_FILE_BYTES
                val lines = when {
                    fresh -> emptyList()
                    file.exists() -> file.readLines().filter { it.isNotBlank() }
                    else -> emptyList()
                }
                val entry = json.encodeToString(CrashRecord.serializer(), record)
                val kept = (lines + entry)
                    .filter { it.isNotBlank() }
                    .takeLast(MAX_ENTRIES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }

        /** All recorded crashes, newest last. Never throws. */
        fun read(dir: File): List<CrashRecord> {
            val file = logFile(dir)
            if (!file.exists()) return emptyList()
            return runCatching {
                file.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        runCatching {
                            json.decodeFromString(CrashRecord.serializer(), line)
                        }.getOrNull()
                    }
            }.getOrDefault(emptyList())
        }

        /** Most recent crash, or null when the log is empty. Never throws. */
        fun latest(dir: File): CrashRecord? = read(dir).lastOrNull()

        /** Clears the log (called when the user dismisses the report). */
        fun clear(dir: File) {
            io.execute { runCatching { logFile(dir).delete() } }
        }

        /** Human-readable one-liner for a UI row. */
        fun CrashRecord.summary(): String {
            val whenText = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
            return "$whenText · $message"
        }

        private const val MAX_FILE_BYTES = 256 * 1024L
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLoggingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLoggingHandler(this, previous))
    }

    private class CrashLoggingHandler(
        private val app: Application,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            // Never let crash logging itself cause a second failure.
            runCatching {
                Timber.e(throwable, "FATAL: uncaught exception on ${thread.name}")
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                val record = CrashRecord(
                    timestamp = System.currentTimeMillis(),
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    thread = thread.name,
                    message = throwable.message ?: throwable::class.java.name,
                    stackTrace = sw.toString()
                )
                CrashLog.record(app.filesDir, record)
            }
            // Delegate so the platform behaves exactly as it would have.
            (previous ?: Thread.getDefaultUncaughtExceptionHandler())?.let {
                if (it !== this) it.uncaughtException(thread, throwable)
            }
        }
    }
}

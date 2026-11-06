package com.pixelpal.app.data.remote.firebase

import com.pixelpal.app.data.local.db.dao.BondDao
import com.pixelpal.app.data.local.db.dao.CompanionDao
import com.pixelpal.app.data.local.db.dao.ReminderDao
import com.pixelpal.app.data.local.db.dao.SubtaskDao
import com.pixelpal.app.data.local.db.dao.TaskDao
import com.pixelpal.app.data.local.db.entity.BondEntity
import com.pixelpal.app.data.local.db.entity.CompanionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates true 2-way Cloud Firestore synchronization:
 *
 *  - Cloud → local: full pull-on-login plus real-time snapshot listeners that
 *    apply remote tasks/reminders/subtasks into Room with last-write-wins on
 *    [SyncPolicy.effectiveUpdatedAt] (`max(updatedAt, syncServerTs)`).
 *    Remote changes are written through the DAOs directly (never the
 *    repositories), so they never echo back to the cloud.
 *  - Local → cloud: repositories push each write through the engine, and
 *    [fullSync] batch-pushes everything (also the hourly worker path).
 *  - Deletes are TOMBSTONES: a `deleted = true` cloud document removes the
 *    matching local row (never inserts one) and is never overwritten by a
 *    plain push, so an offline device cannot resurrect a deleted row.
 *  - Companion & bond are singleton documents restored from the cloud only
 *    when the newer side wins on `updatedAt` (last-write-wins).
 *  - Rows that arrive before the local account is materialised (no primary
 *    companion / no parent task yet) are BUFFERED in memory and re-applied via
 *    [drainPendingApplies] instead of being silently dropped.
 *  - [signOut] wipes all local user data BEFORE auth sign-out so the next
 *    account (or guest) never inherits the previous user's data.
 */
@Singleton
class FirestoreSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val companionDao: CompanionDao,
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val subtaskDao: SubtaskDao,
    private val bondDao: BondDao,
    private val syncEngine: FirestoreSyncEngine,
    private val authManager: FirebaseAuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    /** Last full-sync failure message (null when the last sync succeeded). */
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    val isUserLoggedIn: Boolean
        get() = syncEngine.isUserLoggedIn

    val isAnonymousUser: Boolean
        get() = syncEngine.isAnonymousUser

    val currentUserEmail: String?
        get() = syncEngine.currentUserEmail

    /** Bumped on every sign-out so a stale in-flight sync can abort instead of clobbering. */
    private var sessionEpoch = 0

    private var realtimeUid: String? = null
    private var realtimeJobs = emptyList<kotlinx.coroutines.Job>()

    // ------------------------------------------------------------------
    // Pending-apply buffer (cloud rows that could not be attached to Room yet)
    // ------------------------------------------------------------------

    /** A cloud row awaiting its local anchor (companion row / parent task). */
    private sealed interface PendingApply {
        val cloudId: String
        val cloudEffective: Long

        data class Task(
            override val cloudId: String,
            override val cloudEffective: Long,
            val row: FirestoreTask
        ) : PendingApply

        data class Reminder(
            override val cloudId: String,
            override val cloudEffective: Long,
            val row: FirestoreReminder
        ) : PendingApply

        data class Subtask(
            override val cloudId: String,
            override val cloudEffective: Long,
            val row: FirestoreSubtask
        ) : PendingApply
    }

    /** Insertion-ordered (oldest first) so a parent task is attached before its subtasks. */
    private val pendingApplies = linkedMapOf<String, PendingApply>()

    /** Serialises buffer mutations and drains (realtime + pull paths race). */
    private val pendingLock = Mutex()

    /** Cap so a long signed-out/first-run period cannot grow the buffer unbounded. */
    private companion object {
        const val MAX_PENDING_APPLIES = 2_000
    }

    /** Number of buffered cloud rows (diagnostics / tests). */
    val pendingApplyCount: Int
        get() = pendingApplies.size

    /**
     * Starts the real-time cloud → local listeners, pinned to the CURRENT
     * authenticated uid. Safe to call repeatedly; no-ops when signed out so a
     * later [onUserAuthenticated] can start them once auth is ready.
     *
     * Previously this captured the uid once at Application start (usually
     * signed-out) and never rebound after login, so post-login realtime sync
     * silently never ran.
     */
    fun startRealtimeSync() {
        val uid = syncEngine.currentUserId
        if (uid.isNullOrBlank()) {
            Timber.d("[FIRESTORE] Realtime sync deferred: no authenticated user yet")
            return
        }
        if (realtimeUid == uid && realtimeJobs.any { it.isActive }) return
        cancelRealtimeSync()
        realtimeUid = uid
        Timber.d("[FIRESTORE] Starting realtime listeners for uid=%s", uid)
        val epoch = sessionEpoch

        realtimeJobs = listOf(
            scope.launch {
                syncEngine.observeCloudTasksFor(uid).collect { remoteTasks ->
                    if (epoch != sessionEpoch) return@collect
                    runCatching {
                        drainPendingApplies()
                        remoteTasks.forEach { applyCloudTask(it) }
                    }.onFailure { Timber.e(it, "[FIRESTORE] Failed applying cloud tasks") }
                }
            },
            scope.launch {
                syncEngine.observeCloudRemindersFor(uid).collect { remoteReminders ->
                    if (epoch != sessionEpoch) return@collect
                    runCatching {
                        drainPendingApplies()
                        remoteReminders.forEach { applyCloudReminder(it) }
                    }.onFailure { Timber.e(it, "[FIRESTORE] Failed applying cloud reminders") }
                }
            },
            scope.launch {
                syncEngine.observeCloudSubtasksFor(uid).collect { remoteSubtasks ->
                    if (epoch != sessionEpoch) return@collect
                    runCatching {
                        drainPendingApplies()
                        remoteSubtasks.forEach { applyCloudSubtask(it) }
                    }.onFailure { Timber.e(it, "[FIRESTORE] Failed applying cloud subtasks") }
                }
            }
        )
    }

    /**
     * Call immediately after Firebase Auth succeeds (uid available): rebinds
     * realtime listeners to the new uid and runs a full pull → push restore.
     * Awaiting the returned Result before navigating guarantees the companion
     * and metrics are in Room before Home renders.
     */
    suspend fun onUserAuthenticated(): Result<Unit> {
        val uid = syncEngine.currentUserId
        Timber.d("[AUTH] Session established, uid=%s. Rebinding sync.", uid)
        startRealtimeSync()
        return fullSync()
    }

    private fun cancelRealtimeSync() {
        realtimeJobs.forEach { it.cancel() }
        realtimeJobs = emptyList()
        realtimeUid = null
    }

    /**
     * Full bidirectional sync: pull cloud → local, then push local → cloud.
     * Used after sign-in, by the periodic worker, and by "Sync Now".
     */
    suspend fun fullSync(): Result<Unit> {
        val uid = syncEngine.currentUserId
        if (uid.isNullOrBlank()) {
            Timber.w("[FIRESTORE] fullSync skipped: no authenticated user")
            return Result.failure(IllegalStateException("No user logged in"))
        }
        val epoch = sessionEpoch
        _isSyncing.value = true
        try {
            Timber.d("[FIRESTORE] fullSync start for uid=%s", uid)
            val tombstoned = HashSet<String>()
            pullAndApplyCloud(tombstoned)
            if (epoch != sessionEpoch) {
                Timber.w("[FIRESTORE] fullSync aborted: session changed mid-sync")
                return Result.failure(java.util.concurrent.CancellationException("Session changed"))
            }

            val primary = companionDao.getPrimaryDirect()
            val tasks = primary?.let { taskDao.getTasksDirect(it.id) } ?: emptyList()
            val subtasks = subtaskDao.getAllDirect()
            val reminders = reminderDao.getAllRemindersDirect()
            val bond = primary?.let { bondDao.getBondDirect(it.id) }

            val pushResult = syncEngine.pushAllLocalToCloud(
                companion = primary?.toDomainModel(),
                tasks = tasks,
                subtasks = subtasks,
                reminders = reminders,
                bond = bond?.toDomainModel(),
                tombstonedIds = tombstoned
            )
            if (pushResult.isFailure) {
                val msg = pushResult.exceptionOrNull()?.message
                _lastSyncError.value = msg
                Timber.w("[FIRESTORE] fullSync push failed for uid=%s: %s", uid, msg)
            } else {
                _lastSyncError.value = null
                Timber.d(
                    "[FIRESTORE] fullSync complete for uid=%s (companion=%s, %d writes)",
                    uid, primary?.name, pushResult.getOrDefault(0)
                )
            }
            return pushResult.map { }
        } finally {
            _isSyncing.value = false
        }
    }

    /** Fire-and-forget full sync (sign-in path). */
    fun syncInBackground() {
        scope.launch { fullSync() }
    }

    /**
     * Pull-on-login restore: applies cloud tasks/reminders with last-write-wins,
     * and restores companion/bond from the cloud when the cloud copy is newer.
     *
     * [tombstonedOut] collects every cloud id seen as a tombstone so
     * [pushAllLocalToCloud] never overwrites one.
     */
    private suspend fun pullAndApplyCloud(tombstonedOut: MutableSet<String> = HashSet()) {
        syncEngine.pullTasksFromCloud().onSuccess { remoteTasks ->
            drainPendingApplies()
            remoteTasks.forEach {
                if (it.model.deleted) tombstonedOut += it.model.id
                applyCloudTask(it)
            }
        }.onFailure { Timber.w(it, "Pull tasks failed") }

        syncEngine.pullRemindersFromCloud().onSuccess { remoteReminders ->
            drainPendingApplies()
            remoteReminders.forEach {
                if (it.model.deleted) tombstonedOut += it.model.id
                applyCloudReminder(it)
            }
        }.onFailure { Timber.w(it, "Pull reminders failed") }

        syncEngine.pullSubtasksFromCloud().onSuccess { remoteSubtasks ->
            drainPendingApplies()
            remoteSubtasks.forEach {
                if (it.model.deleted) tombstonedOut += it.model.id
                applyCloudSubtask(it)
            }
        }.onFailure { Timber.w(it, "Pull subtasks failed") }

        // Companion is pulled before draining: tasks/reminders buffered while no
        // primary existed can be attached the moment it lands.
        applyCloudCompanion()
        drainPendingApplies()
        applyCloudBond()
    }

    // ------------------------------------------------------------------
    // Apply (cloud → local)
    // ------------------------------------------------------------------

    private suspend fun applyCloudTask(cloud: CloudRow<FirestoreTask>) {
        val row = cloud.model
        if (row.id.isBlank()) return
        val effective = SyncPolicy.effectiveUpdatedAt(row.updatedAt, cloud.serverTsMillis)
        val local = taskDao.getByCloudId(row.id)
        when (
            SyncPolicy.mergeAction(
                cloudDeleted = row.deleted,
                cloudEffective = effective,
                localExists = local != null,
                localUpdatedAt = local?.updatedAt ?: 0L
            )
        ) {
            SyncPolicy.MergeAction.UPDATE -> {
                val companionId = companionDao.getPrimaryDirect()?.id
                if (companionId == null) {
                    // P0 #1: a task cannot exist without its companion row (FK
                    // CASCADE). Buffer instead of dropping it on the floor.
                    buffer(PendingApply.Task(row.id, effective, row))
                    return
                }
                if (local == null) {
                    taskDao.insert(row.toEntity(localId = 0, companionId = companionId))
                } else if (SyncPolicy.shouldApplyRemote(effective, local.updatedAt)) {
                    taskDao.update(
                        row.toEntity(localId = local.id, companionId = local.companionId)
                            .copy(updatedAt = SyncPolicy.localTimestampAfterApply(effective, local.updatedAt))
                    )
                }
            }
            SyncPolicy.MergeAction.TOMBSTONE -> {
                local?.let { taskDao.delete(it) }
                Timber.d("[FIRESTORE] Task %s deleted remotely (tombstone)", row.id)
            }
            SyncPolicy.MergeAction.KEEP_LOCAL ->
                Timber.d("[FIRESTORE] Local task %s is newer than cloud tombstone; keeping", row.id)
            SyncPolicy.MergeAction.SKIP -> Unit
        }
    }

    private suspend fun applyCloudReminder(cloud: CloudRow<FirestoreReminder>) {
        val row = cloud.model
        if (row.id.isBlank()) return
        val effective = SyncPolicy.effectiveUpdatedAt(row.updatedAt, cloud.serverTsMillis)
        val local = reminderDao.getByCloudId(row.id)
        when (
            SyncPolicy.mergeAction(
                cloudDeleted = row.deleted,
                cloudEffective = effective,
                localExists = local != null,
                localUpdatedAt = local?.updatedAt ?: 0L
            )
        ) {
            SyncPolicy.MergeAction.UPDATE -> {
                val companionId = companionDao.getPrimaryDirect()?.id
                if (companionId == null && local == null) {
                    // companionId is SET NULL on reminders, so a reminder *could*
                    // be inserted unattached — but it would then be invisible to
                    // the per-companion flows and never repaired. Buffer instead.
                    buffer(PendingApply.Reminder(row.id, effective, row))
                    return
                }
                if (local == null) {
                    reminderDao.insert(row.toEntity(localId = 0, companionId = companionId))
                } else {
                    reminderDao.update(
                        row.toEntity(localId = local.id, companionId = local.companionId)
                            .copy(updatedAt = SyncPolicy.localTimestampAfterApply(effective, local.updatedAt))
                    )
                }
            }
            SyncPolicy.MergeAction.TOMBSTONE -> {
                local?.let { reminderDao.delete(it) }
                Timber.d("[FIRESTORE] Reminder %s deleted remotely (tombstone)", row.id)
            }
            SyncPolicy.MergeAction.KEEP_LOCAL ->
                Timber.d("[FIRESTORE] Local reminder %s is newer than cloud tombstone; keeping", row.id)
            SyncPolicy.MergeAction.SKIP -> Unit
        }
    }

    private suspend fun applyCloudSubtask(cloud: CloudRow<FirestoreSubtask>) {
        val row = cloud.model
        if (row.id.isBlank() || row.parentCloudId.isBlank()) return
        val effective = SyncPolicy.effectiveUpdatedAt(row.updatedAt, cloud.serverTsMillis)
        val local = subtaskDao.getByCloudId(row.id)
        when (
            SyncPolicy.mergeAction(
                cloudDeleted = row.deleted,
                cloudEffective = effective,
                localExists = local != null,
                localUpdatedAt = local?.updatedAt ?: 0L
            )
        ) {
            SyncPolicy.MergeAction.UPDATE -> {
                val parent = taskDao.getByCloudId(row.parentCloudId)
                if (parent == null) {
                    // The parent task has not arrived yet (listener ordering).
                    buffer(PendingApply.Subtask(row.id, effective, row))
                    return
                }
                if (local == null) {
                    subtaskDao.insert(row.toSubtaskEntity(localId = 0, taskId = parent.id))
                } else if (local.taskId != parent.id || SyncPolicy.shouldApplyRemote(effective, local.updatedAt)) {
                    subtaskDao.update(
                        row.toSubtaskEntity(localId = local.id, taskId = parent.id)
                            .copy(updatedAt = SyncPolicy.localTimestampAfterApply(effective, local.updatedAt))
                    )
                }
            }
            SyncPolicy.MergeAction.TOMBSTONE -> {
                local?.let { subtaskDao.delete(it) }
                Timber.d("[FIRESTORE] Subtask %s deleted remotely (tombstone)", row.id)
            }
            SyncPolicy.MergeAction.KEEP_LOCAL ->
                Timber.d("[FIRESTORE] Local subtask %s is newer than cloud tombstone; keeping", row.id)
            SyncPolicy.MergeAction.SKIP -> Unit
        }
    }

    private suspend fun applyCloudCompanion() {
        val cloudResult = syncEngine.pullCompanionFromCloud()
        val cloudRow = cloudResult.getOrNull()
        if (cloudRow == null) {
            if (cloudResult.isFailure) {
                Timber.w(
                    "[FIRESTORE] Companion pull failed: %s",
                    cloudResult.exceptionOrNull()?.message
                )
            } else {
                Timber.d("[FIRESTORE] No cloud companion yet (new user)")
            }
            return
        }
        val cloud = cloudRow.model
        val cloudEffective = SyncPolicy.effectiveUpdatedAt(cloud.updatedAt, cloudRow.serverTsMillis)
        val local = companionDao.getPrimaryDirect()
        if (local == null) {
            companionDao.insert(cloud.toEntityWithTimestamps(cloudUpdatedAt = cloudEffective))
            Timber.d("[FIRESTORE] Companion restored from cloud (name=%s)", cloud.name)
        } else if (SyncPolicy.shouldApplyRemote(cloudEffective, local.updatedAt) || isFreshUneditedLocal(local)) {
            // A fresh bootstrap seed (createdAt == updatedAt, never edited) always
            // loses to an existing cloud row: otherwise a post-wipe reseed with a
            // newer timestamp would permanently hide the user's real companion.
            companionDao.update(
                cloud.toEntityWithTimestamps(cloudUpdatedAt = cloudEffective, local = local)
            )
            Timber.d("[FIRESTORE] Companion restored from cloud (name=%s)", cloud.name)
        }
    }

    /** True when the local row is a just-seeded default the user never edited. */
    private fun isFreshUneditedLocal(local: CompanionEntity): Boolean =
        local.createdAt == local.updatedAt

    private fun CompanionEntity.toDomainModel() = com.pixelpal.app.domain.model.Companion(
        id = id,
        name = name,
        petType = petType,
        role = com.pixelpal.app.domain.model.CompanionRole.fromId(role),
        description = description,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        isFavorite = isFavorite,
        isArchived = isArchived,
        hatId = hatId,
        outfitId = outfitId,
        accessoryId = accessoryId,
        species = species,
        color = color,
        pattern = pattern
    )

    private fun BondEntity.toDomainModel() = com.pixelpal.app.domain.model.Bond(
        companionId = companionId,
        level = level,
        totalInteractions = totalInteractions,
        tapsToday = tapsToday,
        feedsToday = feedsToday,
        lastInteractionTime = lastInteractionTime,
        streakDays = streakDays,
        lastStreakDate = lastStreakDate
    )

    private suspend fun FirestoreCompanion.toEntityWithTimestamps(
        cloudUpdatedAt: Long,
        local: CompanionEntity? = null
    ): CompanionEntity {
        val domain = toDomain(id = local?.id ?: 0)
        return CompanionEntity(
            id = domain.id,
            name = domain.name,
            petType = domain.petType,
            role = domain.role.id,
            description = domain.description,
            createdAt = local?.createdAt ?: System.currentTimeMillis(),
            lastUsedAt = local?.lastUsedAt,
            isFavorite = domain.isFavorite,
            isArchived = local?.isArchived ?: false,
            hatId = domain.hatId,
            outfitId = domain.outfitId,
            accessoryId = domain.accessoryId,
            species = domain.species,
            color = domain.color,
            pattern = domain.pattern,
            updatedAt = cloudUpdatedAt
        )
    }

    private suspend fun applyCloudBond() {
        val cloudResult = syncEngine.pullBondFromCloud()
        val cloudRow = cloudResult.getOrNull()
        if (cloudRow == null) {
            if (cloudResult.isFailure) {
                Timber.w(
                    "[FIRESTORE] Bond pull failed: %s",
                    cloudResult.exceptionOrNull()?.message
                )
            }
            return
        }
        val cloud = cloudRow.model
        val cloudEffective = SyncPolicy.effectiveUpdatedAt(cloud.updatedAt, cloudRow.serverTsMillis)
        val primary = companionDao.getPrimaryDirect() ?: return
        val local = bondDao.getBondDirect(primary.id)
        if (local == null) {
            bondDao.insertOrUpdate(
                BondEntity(
                    companionId = primary.id,
                    level = cloud.level,
                    totalInteractions = cloud.totalInteractions,
                    tapsToday = cloud.tapsToday,
                    feedsToday = cloud.feedsToday,
                    lastInteractionTime = cloud.lastInteractionTime,
                    streakDays = cloud.streakDays,
                    lastStreakDate = cloud.lastStreakDate,
                    updatedAt = cloudEffective
                )
            )
            Timber.d("[FIRESTORE] Bond metrics restored from cloud (level=%d)", cloud.level)
        } else if (SyncPolicy.shouldApplyRemote(cloudEffective, local.updatedAt) || isFreshDefaultBond(local)) {
            bondDao.insertOrUpdate(
                local.copy(
                    level = cloud.level,
                    totalInteractions = cloud.totalInteractions,
                    tapsToday = cloud.tapsToday,
                    feedsToday = cloud.feedsToday,
                    lastInteractionTime = cloud.lastInteractionTime,
                    streakDays = cloud.streakDays,
                    lastStreakDate = cloud.lastStreakDate,
                    updatedAt = SyncPolicy.localTimestampAfterApply(cloudEffective, local.updatedAt)
                )
            )
        }
    }

    /** True when the local bond row is an untouched ensureExists() default. */
    private fun isFreshDefaultBond(local: BondEntity): Boolean =
        local.totalInteractions == 0 && local.level <= 0 && local.updatedAt == 0L

    // ------------------------------------------------------------------
    // Pending-apply buffer
    // ------------------------------------------------------------------

    private suspend fun buffer(pending: PendingApply) {
        pendingLock.withLock {
            if (pendingApplies.size >= MAX_PENDING_APPLIES) {
                val evicted = pendingApplies.remove(pendingApplies.keys.first())
                Timber.w(
                    "[FIRESTORE] Pending-apply buffer full (%d); evicting oldest %s",
                    MAX_PENDING_APPLIES, evicted?.cloudId
                )
            }
            // Newest cloud state per cloudId wins (realtime updates supersede).
            pendingApplies[pending.cloudId] = pending
        }
        Timber.w(
            "[FIRESTORE] Buffered cloud row %s (no primary companion / parent task yet); will retry after bootstrap",
            pending.cloudId
        )
    }

    /**
     * Re-applies every buffered cloud row that can now be attached to Room.
     *
     * Called before each apply batch (realtime and pull paths) and after the
     * companion is restored in [fullSync]. Rows that still have no anchor stay
     * buffered; nothing is ever dropped silently.
     *
     * @return number of rows drained.
     */
    suspend fun drainPendingApplies(): Int {
        if (pendingApplies.isEmpty()) return 0
        // Nothing can be attached before the primary companion exists; the
        // buffer is drained again right after the companion is restored.
        if (companionDao.getPrimaryDirect() == null) return 0
        val drained = pendingLock.withLock {
            val snapshot = pendingApplies.values.toList()
            pendingApplies.clear()
            snapshot
        }
        var applied = 0
        for (pending in drained) {
            when (pending) {
                is PendingApply.Task ->
                    // Re-applying a task that is still unattachable re-buffers it.
                    if (companionDao.getPrimaryDirect() != null) {
                        taskDao.getByCloudId(pending.cloudId)?.let { local ->
                            if (SyncPolicy.shouldApplyRemote(pending.cloudEffective, local.updatedAt)) {
                                taskDao.update(
                                    pending.row.toEntity(localId = local.id, companionId = local.companionId)
                                        .copy(updatedAt = SyncPolicy.localTimestampAfterApply(pending.cloudEffective, local.updatedAt))
                                )
                            }
                        } ?: taskDao.insert(
                            pending.row.toEntity(localId = 0, companionId = companionDao.getPrimaryDirect()!!.id)
                        )
                        applied++
                    } else {
                        buffer(pending)
                    }
                is PendingApply.Reminder ->
                    if (companionDao.getPrimaryDirect() != null) {
                        reminderDao.getByCloudId(pending.cloudId)?.let { local ->
                            if (SyncPolicy.shouldApplyRemote(pending.cloudEffective, local.updatedAt)) {
                                reminderDao.update(
                                    pending.row.toEntity(localId = local.id, companionId = local.companionId)
                                        .copy(updatedAt = SyncPolicy.localTimestampAfterApply(pending.cloudEffective, local.updatedAt))
                                )
                            }
                        } ?: reminderDao.insert(
                            pending.row.toEntity(localId = 0, companionId = companionDao.getPrimaryDirect()!!.id)
                        )
                        applied++
                    } else {
                        buffer(pending)
                    }
                is PendingApply.Subtask -> {
                    val parent = taskDao.getByCloudId(pending.row.parentCloudId)
                    if (parent == null) {
                        buffer(pending)
                    } else {
                        subtaskDao.getByCloudId(pending.cloudId)?.let { local ->
                            if (local.taskId != parent.id || SyncPolicy.shouldApplyRemote(pending.cloudEffective, local.updatedAt)) {
                                subtaskDao.update(
                                    pending.row.toSubtaskEntity(localId = local.id, taskId = parent.id)
                                        .copy(updatedAt = SyncPolicy.localTimestampAfterApply(pending.cloudEffective, local.updatedAt))
                                )
                            }
                        } ?: subtaskDao.insert(pending.row.toSubtaskEntity(localId = 0, taskId = parent.id))
                        applied++
                    }
                }
            }
        }
        if (applied > 0) {
            Timber.i(
                "[FIRESTORE] Drained %d buffered cloud rows (%d still waiting for their anchor)",
                applied, pendingApplies.size
            )
        }
        return applied
    }

    /** Drops buffered rows without applying them (sign-out / account switch). */
    private suspend fun clearPendingApplies() {
        val dropped = pendingLock.withLock {
            val n = pendingApplies.size
            pendingApplies.clear()
            n
        }
        if (dropped > 0) {
            Timber.d("[FIRESTORE] Cleared %d buffered cloud rows on sign-out", dropped)
        }
    }

    /**
     * Reflects a cloud tombstone discovered by a refused PUSH (§push guards) in
     * Room, so a row deleted on another device disappears here as well instead
     * of being pushed forever.
     */
    private suspend fun mirrorPushTombstone(type: String, cloudId: String) {
        when (type) {
            FirestorePushWorkerTypes.TASK ->
                taskDao.getByCloudId(cloudId)?.let { taskDao.delete(it) }
            FirestorePushWorkerTypes.REMINDER ->
                reminderDao.getByCloudId(cloudId)?.let { reminderDao.delete(it) }
            FirestorePushWorkerTypes.SUBTASK ->
                subtaskDao.getByCloudId(cloudId)?.let { subtaskDao.delete(it) }
        }
        Timber.i("[FIRESTORE] Applied remote tombstone for %s %s", type, cloudId)
    }

    /** Result of pushing one entity on behalf of the retry worker. */
    enum class PushOutcome {
        /** The cloud now holds this write. */
        DONE,

        /** A newer cloud tombstone won; the local row was removed. */
        REMOVED_REMOTELY,

        /** Transport/exception failure — let WorkManager retry with backoff. */
        RETRY
    }

    /**
     * Pushes one entity by cloud id (the retry-worker path).
     *
     * Unlike the listener path, a refused push here means the *cloud* holds a
     * tombstone that outranks this local row, so the row is removed locally
     * instead of being re-pushed forever.
     */
    suspend fun pushEntity(type: String, cloudId: String): PushOutcome {
        return try {
            val outcome = when (type) {
                FirestorePushWorkerTypes.TASK -> {
                    val entity = taskDao.getByCloudId(cloudId) ?: return PushOutcome.DONE
                    syncEngine.syncTaskToCloud(entity)
                }
                FirestorePushWorkerTypes.REMINDER -> {
                    val entity = reminderDao.getByCloudId(cloudId) ?: return PushOutcome.DONE
                    syncEngine.syncReminderToCloud(entity)
                }
                FirestorePushWorkerTypes.SUBTASK -> {
                    val subtask = subtaskDao.getByCloudId(cloudId) ?: return PushOutcome.DONE
                    val parent = taskDao.getTaskById(subtask.taskId) ?: return PushOutcome.DONE
                    syncEngine.syncSubtaskToCloud(subtask, parent.cloudId)
                }
                FirestorePushWorkerTypes.DELETE_TASK -> syncEngine.deleteTaskFromCloud(cloudId)
                FirestorePushWorkerTypes.DELETE_REMINDER -> syncEngine.deleteReminderFromCloud(cloudId)
                FirestorePushWorkerTypes.DELETE_SUBTASK -> syncEngine.deleteSubtaskFromCloud(cloudId)
                else -> {
                    Timber.w("[FIRESTORE] Unknown push type %s", type)
                    return PushOutcome.DONE
                }
            }
            val pushed = outcome.getOrElse {
                Timber.w(it, "[FIRESTORE] Push %s %s failed", type, cloudId)
                return PushOutcome.RETRY
            }
            if (pushed) {
                PushOutcome.DONE
            } else {
                mirrorPushTombstone(entityTypeOf(type), cloudId)
                PushOutcome.REMOVED_REMOTELY
            }
        } catch (e: Exception) {
            Timber.e(e, "[FIRESTORE] Push %s %s threw", type, cloudId)
            PushOutcome.RETRY
        }
    }

    private fun entityTypeOf(pushType: String): String = when (pushType) {
        FirestorePushWorkerTypes.DELETE_TASK -> FirestorePushWorkerTypes.TASK
        FirestorePushWorkerTypes.DELETE_REMINDER -> FirestorePushWorkerTypes.REMINDER
        FirestorePushWorkerTypes.DELETE_SUBTASK -> FirestorePushWorkerTypes.SUBTASK
        else -> pushType
    }

    /**
     * Wipes ALL local user data, then signs out of Firebase. Guarantees the
     * next sign-in (any account, including guest) starts clean instead of
     * pushing the previous user's data under the new uid.
     *
     * The wipe runs BEFORE auth sign-out in the same coroutine, the realtime
     * listeners are cancelled, the pending-apply buffer is cleared, and
     * [sessionEpoch] is bumped so any in-flight fullSync aborts instead of
     * re-pushing stale rows under the next uid.
     */
    fun signOut() {
        scope.launch { signOutSuspend() }
    }

    /**
     * @return true when the local wipe succeeded and the auth session ended.
     *
     * Fails **closed**: if the wipe cannot complete, the user stays signed in
     * rather than handing the next account a DB full of the previous user's
     * rows (which the next sync would then push under the new uid).
     */
    suspend fun signOutSuspend(): Boolean {
        sessionEpoch++
        cancelRealtimeSync()
        clearPendingApplies()

        val wiped = try {
            clearAllLocalData()
            true
        } catch (first: Exception) {
            Timber.w(first, "[AUTH] Local wipe failed, retrying once")
            try {
                clearAllLocalData()
                true
            } catch (second: Exception) {
                Timber.e(second, "[AUTH] Local wipe failed twice — sign-out aborted")
                false
            }
        }
        if (!wiped) {
            _lastSyncError.value =
                "Couldn't clear local data, so sign-out was cancelled. Try again."
            return false
        }

        Timber.d("[AUTH] Signed out, local session cleared")
        authManager.signOut()
        return true
    }

    private suspend fun clearAllLocalData() {
        subtaskDao.deleteAll()
        taskDao.deleteAll()
        reminderDao.deleteAll()
        bondDao.deleteAll()
        companionDao.deleteAll()
        Timber.d("Local user data cleared for sign-out")
    }
}

/**
 * Push types shared with `FirestorePushWorker` without importing the
 * Android/WorkManager layer into this file's data class.
 */
object FirestorePushWorkerTypes {
    const val TASK = com.pixelpal.app.worker.FirestorePushWorker.TYPE_TASK
    const val REMINDER = com.pixelpal.app.worker.FirestorePushWorker.TYPE_REMINDER
    const val SUBTASK = com.pixelpal.app.worker.FirestorePushWorker.TYPE_SUBTASK
    const val DELETE_TASK = com.pixelpal.app.worker.FirestorePushWorker.TYPE_DELETE_TASK
    const val DELETE_REMINDER = com.pixelpal.app.worker.FirestorePushWorker.TYPE_DELETE_REMINDER
    const val DELETE_SUBTASK = com.pixelpal.app.worker.FirestorePushWorker.TYPE_DELETE_SUBTASK
}

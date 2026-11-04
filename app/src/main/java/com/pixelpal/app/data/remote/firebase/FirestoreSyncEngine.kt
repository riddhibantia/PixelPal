package com.pixelpal.app.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.pixelpal.app.data.local.db.entity.ReminderEntity
import com.pixelpal.app.data.local.db.entity.SubtaskEntity
import com.pixelpal.app.data.local.db.entity.TaskEntity
import com.pixelpal.app.domain.model.Bond
import com.pixelpal.app.domain.model.Companion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One cloud document plus the timing facts needed for conflict resolution.
 *
 * [serverTsMillis] is Firestore's own commit time (`serverTs` field, written as
 * [FieldValue.serverTimestamp]); it is null for documents written by older app
 * versions and for not-yet-committed local cache entries, in which case
 * [SyncPolicy.effectiveUpdatedAt] falls back to the writer-side `updatedAt`.
 */
@IgnoreExtraProperties
data class CloudRow<T>(
    val model: T,
    val serverTsMillis: Long?
)

internal fun <T> QuerySnapshot.toCloudRows(mapper: (DocumentSnapshot) -> T?): List<CloudRow<T>> = documents.mapNotNull { doc ->
    mapper(doc)?.let { model ->
        CloudRow(
            model = model,
            serverTsMillis = doc.getTimestamp(SyncPolicy.FIELD_SERVER_TS)?.toDate()?.time
        )
    }
}

/**
 * Real-time Cloud Firestore replication: offline-first local persistence with
 * cloud backup and last-write-wins conflict resolution.
 *
 * Cloud document ids for tasks/reminders are the local row's stable `cloudId`
 * UUID — never the device-local Room autoincrement id, which collides across
 * devices.
 */
@Singleton
class FirestoreSyncEngine @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    /** Engine-owned scope so pushes never block the caller (UI coroutine) on network I/O. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentUserId: String?
        get() = auth.currentUser?.uid

    val currentUserEmail: String?
        get() = auth.currentUser?.email

    val isUserLoggedIn: Boolean
        get() = auth.currentUser != null

    val isAnonymousUser: Boolean
        get() = auth.currentUser?.isAnonymous == true

    /**
     * Logs Firestore failures with the authenticated UID (never tokens/keys)
     * and a PERMISSION_DENIED hint. Permission denials under locked-down
     * rules almost always mean: signed out, wrong uid path, or wrong Firebase
     * project — never a reason to reopen the rules.
     */
    private fun logFirestoreError(op: String, e: Exception) {
        val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
        Timber.e(e, "[FIRESTORE] %s failed (uid=%s, code=%s)", op, currentUserId, code)
        if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            Timber.w(
                "[FIRESTORE] PERMISSION_DENIED on %s: auth uid does not match " +
                    "users/{uid} path, session not ready, or wrong Firebase project. " +
                    "Keep rules locked; fix the app path/session.",
                op
            )
        }
    }

    private fun tasksRef(uid: String) =
        firestore.collection("users").document(uid).collection("tasks")

    private fun remindersRef(uid: String) =
        firestore.collection("users").document(uid).collection("reminders")

    private fun subtasksRef(uid: String) =
        firestore.collection("users").document(uid).collection("subtasks")

    // ==========================================
    // SHARED WRITE/RESOLUTION PLUMBING
    // ==========================================

    /**
     * Local write timestamp for a soft-delete tombstone. Kept separate from the
     * server timestamp so [SyncPolicy.effectiveUpdatedAt] always has a real
     * writer-side value to fall back on (an offline delete is queued by
     * WorkManager and only stamped when the write finally commits).
     */
    private fun now(): Long = System.currentTimeMillis()

    /**
     * The document payload for a delete. Soft deletes (`deleted = true`) are
     * sticky: they can only be overwritten by a strictly newer edit, so a peer
     * that never saw the delete can no longer resurrect the row by pushing its
     * stale copy.
     */
    private fun tombstonePayload(deletedAt: Long): Map<String, Any> = mapOf(
        "deleted" to true,
        "updatedAt" to deletedAt
    ) + serverStamp()

    /**
     * Adds Firestore's server commit time to a write payload.
     *
     * One field, reused by deletes and by every entity write, so a document
     * always carries at most one server timestamp (and [SyncPolicy] can compare
     * `max(updatedAt, serverTs)` without ambiguity).
     */
    private fun serverStamp(): Map<String, Any> =
        mapOf(SyncPolicy.FIELD_SERVER_TS to FieldValue.serverTimestamp())

    /**
     * Reads Firestore's own commit time off a snapshot. Absent for documents
     * written before server timestamps existed (and for local pending writes),
     * in which case callers fall back to the writer-side `updatedAt`.
     */
    private fun DocumentSnapshot.serverTsMillis(): Long? =
        getTimestamp(SyncPolicy.FIELD_SERVER_TS)?.toDate()?.time

    private fun DocumentSnapshot.effectiveUpdatedAt(fallbackUpdatedAt: Long): Long =
        SyncPolicy.effectiveUpdatedAt(fallbackUpdatedAt, serverTsMillis())

    /**
     * Soft-deletes a document and tells the caller whether the tombstone
     * actually landed — i.e. whether the cloud document was NOT already
     * carrying a newer edit.
     *
     * That distinction is what stops an offline delete from clobbering a newer
     * row this device has not pulled yet: the repository has already dropped the
     * local row, so if the tombstone is refused the newer cloud row is
     * re-applied locally on the next pull / realtime snapshot and the row
     * reappears with its newer content instead of vanishing silently.
     *
     * @return true when the tombstone won (or there was nothing to tombstone).
     */
    private suspend fun tombstone(
        docRef: DocumentReference,
        deletedAt: Long,
        label: String
    ): Boolean = firestore.runTransaction { txn ->
        val snapshot = txn.get(docRef)
        val canWrite = if (!snapshot.exists()) {
            // Nothing to soft-delete yet (a queued create never reached the
            // cloud). Deliberately not writing a tombstone: the doc is already
            // gone for every peer, and writing one could race a create that is
            // still in flight.
            true
        } else {
            val remoteEffective = snapshot.effectiveUpdatedAt(snapshot.getLong("updatedAt") ?: 0L)
            SyncPolicy.canPushOverExisting(
                cloudDeleted = snapshot.getBoolean("deleted") == true,
                cloudEffective = remoteEffective,
                localUpdatedAt = deletedAt
            )
        }
        if (canWrite) {
            txn.set(docRef, tombstonePayload(deletedAt), SetOptions.merge())
            true
        } else {
            Timber.d("[FIRESTORE] Soft delete of %s refused: cloud copy is newer", label)
            false
        }
    }.await()

    /**
     * Pushes a full default payload unless the cloud document is a tombstone.
     *
     * Absence is never treated as deletion: only an explicit `deleted = true`
     * document blocks a push, and then only when it is not older than the local
     * edit (undo / re-completion after a delete must still win).
     */
    private suspend fun pushGuarded(
        docRef: DocumentReference,
        payload: Map<String, Any>,
        localUpdatedAt: Long,
        label: String
    ): Boolean = firestore.runTransaction { txn ->
        val snapshot = txn.get(docRef)
        val remoteDeleted = snapshot.exists() && snapshot.getBoolean("deleted") == true
        val allowed = SyncPolicy.canPushOverExisting(
            cloudDeleted = remoteDeleted,
            cloudEffective = if (remoteDeleted) snapshot.effectiveUpdatedAt(0L) else 0L,
            localUpdatedAt = localUpdatedAt
        )
        if (allowed) {
            txn.set(docRef, payload, SetOptions.merge())
        } else {
            Timber.w("[FIRESTORE] Push of %s skipped: cloud document is a newer tombstone", label)
        }
        allowed
    }.await()

    /**
     * A refused push is never a transport failure (retrying cannot help), but it
     * does reveal that the cloud holds a newer tombstone. On the listener
     * ([mirrorTombstones]) paths the caller adopts it locally so the row
     * disappears instead of being re-pushed forever.
     */
    private fun logRefused(pushed: Boolean, mirrorRequested: Boolean, label: String) {
        if (!pushed) {
            Timber.i(
                "[FIRESTORE] %s push refused by a newer cloud tombstone (mirror=%s)",
                label, mirrorRequested
            )
        }
    }

    // ==========================================
    // COMPANION SYNC
    // ==========================================

    /**
     * Uploads the active companion to `users/{userId}/companion/primary`.
     */
    suspend fun syncCompanionToCloud(
        companion: Companion,
        updatedAt: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val payload = companion.toFirestore(updatedAt).toMap() + serverStamp()
                firestore.collection("users")
                    .document(uid)
                    .collection("companion")
                    .document("primary")
                    .set(payload, SetOptions.merge())
                    .await()
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync companion to cloud")
                Result.failure(e)
            }
        }
    }

    fun pushCompanionAsync(companion: Companion) {
        scope.launch { syncCompanionToCloud(companion) }
    }

    /**
     * Fetches the current companion from Cloud Firestore, with its server
     * commit time so LWW can use [SyncPolicy.effectiveUpdatedAt].
     */
    suspend fun pullCompanionFromCloud(): Result<CloudRow<FirestoreCompanion>?> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection("users")
                    .document(uid)
                    .collection("companion")
                    .document("primary")
                    .get()
                    .await()
                if (snapshot.exists()) {
                    Result.success(
                        snapshot.toObject(FirestoreCompanion::class.java)?.let {
                            CloudRow(model = it, serverTsMillis = snapshot.serverTsMillis())
                        }
                    )
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                logFirestoreError("pull companion users/{uid}/companion/primary", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Observes real-time changes to the cloud companion.
     *
     * Prefer [observeCloudCompanionFor] with an explicit uid captured AFTER
     * auth completes — the no-arg version snapshots [currentUserId] at
     * collection time and closes immediately when signed out.
     */
    fun observeCloudCompanion(): Flow<FirestoreCompanion?> =
        observeCloudCompanionFor(currentUserId ?: "")

    fun observeCloudCompanionFor(uid: String): Flow<FirestoreCompanion?> = callbackFlow {
        if (uid.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val docRef = firestore.collection("users")
            .document(uid)
            .collection("companion")
            .document("primary")

        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.e(error, "Firestore companion listener error")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                trySend(snapshot.toObject(FirestoreCompanion::class.java))
            } else {
                trySend(null)
            }
        }

        awaitClose { listener.remove() }
    }

    // ==========================================
    // TASKS SYNC
    // ==========================================

    /**
     * Syncs a single task to `users/{userId}/tasks/{cloudId}`.
     *
     * Refuses to overwrite a tombstone that is not older than this local row.
     *
     * @param mirrorTombstones when true (listener/realtime path) a refused push
     *   means the cloud holds a tombstone the local mirror must adopt; the
     *   engine then soft-marks the local row so the next apply removes it. Left
     *   false by the push worker so an explicit re-save still beats a
     *   tombstone.
     * @return Result holding `true` when the payload was pushed. `false` means
     *   a cloud tombstone won.
     */
    suspend fun syncTaskToCloud(
        task: TaskEntity,
        mirrorTombstones: Boolean = false
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (task.cloudId.isBlank()) {
            return Result.failure(IllegalStateException("Task ${task.id} has no cloudId"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val payload = task.toFirestore().toMap() + serverStamp()
                val docRef = tasksRef(uid).document(task.cloudId)
                val pushed = pushGuarded(docRef, payload, task.updatedAt, "task ${task.cloudId}")
                logRefused(pushed, mirrorTombstones, "task ${task.cloudId}")
                Result.success(pushed)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync task to cloud")
                Result.failure(e)
            }
        }
    }

    fun pushTaskAsync(task: TaskEntity) {
        scope.launch { syncTaskToCloud(task) }
    }

    /**
     * Soft-deletes a task from cloud Firestore by its stable cloudId.
     *
     * Succeeds without touching the cloud document when it does not exist yet
     * (there is nothing for a peer to see) rather than writing a tombstone that
     * a still-queued create could turn into a resurrection.
     *
     * @param deletedAt timestamp of the local delete.
     * @return Result holding `true` when the tombstone was written. `false`
     *   means the cloud copy is newer and the delete did not win.
     */
    suspend fun deleteTaskFromCloud(
        cloudId: String,
        deletedAt: Long = now()
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (cloudId.isBlank()) return Result.success(true)
        return withContext(Dispatchers.IO) {
            try {
                Result.success(tombstone(tasksRef(uid).document(cloudId), deletedAt, "task $cloudId"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete task from cloud")
                Result.failure(e)
            }
        }
    }

    fun deleteTaskAsync(cloudId: String, deletedAt: Long = now()) {
        scope.launch { deleteTaskFromCloud(cloudId, deletedAt) }
    }

    /**
     * Fetches all cloud tasks, TOMBSTONES INCLUDED: applying a `deleted = true`
     * document is how a local row learns it was deleted on another device.
     */
    suspend fun pullTasksFromCloud(): Result<List<CloudRow<FirestoreTask>>> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = tasksRef(uid).get().await()
                Result.success(snapshot.toCloudRows { it.toObject(FirestoreTask::class.java) })
            } catch (e: Exception) {
                logFirestoreError("pull tasks users/{uid}/tasks", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Observes real-time changes to the cloud tasks collection.
     *
     * Prefer [observeCloudTasksFor] with an explicit uid captured AFTER auth
     * completes — the no-arg version snapshots [currentUserId] at collection
     * time and closes immediately when signed out.
     */
    fun observeCloudTasks(): Flow<List<CloudRow<FirestoreTask>>> =
        observeCloudTasksFor(currentUserId ?: "")

    fun observeCloudTasksFor(uid: String): Flow<List<CloudRow<FirestoreTask>>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = tasksRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.e(error, "Firestore tasks listener error")
                return@addSnapshotListener
            }
            trySend(snapshot?.toCloudRows { it.toObject(FirestoreTask::class.java) } ?: emptyList())
        }

        awaitClose { listener.remove() }
    }

    // ==========================================
    // REMINDERS SYNC
    // ==========================================

    /**
     * Syncs a single reminder to `users/{userId}/reminders/{cloudId}`.
     */
    suspend fun syncReminderToCloud(
        reminder: ReminderEntity,
        mirrorTombstones: Boolean = false
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (reminder.cloudId.isBlank()) {
            return Result.failure(IllegalStateException("Reminder ${reminder.id} has no cloudId"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val payload = reminder.toFirestore().toMap() + serverStamp()
                val docRef = remindersRef(uid).document(reminder.cloudId)
                val pushed = pushGuarded(docRef, payload, reminder.updatedAt, "reminder ${reminder.cloudId}")
                logRefused(pushed, mirrorTombstones, "reminder ${reminder.cloudId}")
                Result.success(pushed)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync reminder to cloud")
                Result.failure(e)
            }
        }
    }

    fun pushReminderAsync(reminder: ReminderEntity) {
        scope.launch { syncReminderToCloud(reminder) }
    }

    /**
     * Soft-deletes a reminder from Cloud Firestore by its stable cloudId.
     *
     * @return Result holding `true` when the tombstone was written.
     */
    suspend fun deleteReminderFromCloud(
        cloudId: String,
        deletedAt: Long = now()
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (cloudId.isBlank()) return Result.success(true)
        return withContext(Dispatchers.IO) {
            try {
                Result.success(tombstone(remindersRef(uid).document(cloudId), deletedAt, "reminder $cloudId"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete reminder from cloud")
                Result.failure(e)
            }
        }
    }

    fun deleteReminderAsync(cloudId: String, deletedAt: Long = now()) {
        scope.launch { deleteReminderFromCloud(cloudId, deletedAt) }
    }

    /**
     * Fetches all cloud reminders, TOMBSTONES INCLUDED (see [pullTasksFromCloud]).
     */
    suspend fun pullRemindersFromCloud(): Result<List<CloudRow<FirestoreReminder>>> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = remindersRef(uid).get().await()
                Result.success(snapshot.toCloudRows { it.toObject(FirestoreReminder::class.java) })
            } catch (e: Exception) {
                logFirestoreError("pull reminders users/{uid}/reminders", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Observes real-time changes to the cloud reminders collection.
     *
     * Prefer [observeCloudRemindersFor] with an explicit uid captured AFTER
     * auth completes.
     */
    fun observeCloudReminders(): Flow<List<CloudRow<FirestoreReminder>>> =
        observeCloudRemindersFor(currentUserId ?: "")

    fun observeCloudRemindersFor(uid: String): Flow<List<CloudRow<FirestoreReminder>>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = remindersRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.e(error, "Firestore reminders listener error")
                return@addSnapshotListener
            }
            trySend(snapshot?.toCloudRows { it.toObject(FirestoreReminder::class.java) } ?: emptyList())
        }

        awaitClose { listener.remove() }
    }

    // ==========================================
    // BOND & STREAKS SYNC
    // ==========================================

    /**
     * Syncs bond & streak metrics to `users/{userId}/metrics/bond`.
     */
    suspend fun syncBondToCloud(
        bond: Bond,
        updatedAt: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val payload = bond.toFirestore(updatedAt).toMap() + serverStamp()
                firestore.collection("users")
                    .document(uid)
                    .collection("metrics")
                    .document("bond")
                    .set(payload, SetOptions.merge())
                    .await()
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync bond to cloud")
                Result.failure(e)
            }
        }
    }

    fun pushBondAsync(bond: Bond) {
        scope.launch { syncBondToCloud(bond) }
    }

    /**
     * Fetches cloud bond metrics.
     */
    suspend fun pullBondFromCloud(): Result<CloudRow<FirestoreBond>?> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection("users")
                    .document(uid)
                    .collection("metrics")
                    .document("bond")
                    .get()
                    .await()
                if (snapshot.exists()) {
                    Result.success(
                        snapshot.toObject(FirestoreBond::class.java)?.let {
                            CloudRow(model = it, serverTsMillis = snapshot.serverTsMillis())
                        }
                    )
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                logFirestoreError("pull bond users/{uid}/metrics/bond", e)
                Result.failure(e)
            }
        }
    }

    // ==========================================
    // SUBTASKS SYNC
    // ==========================================

    /**
     * Syncs a single subtask to `users/{userId}/subtasks/{cloudId}`,
     * linked to its task via parentCloudId.
     */
    suspend fun syncSubtaskToCloud(
        subtask: SubtaskEntity,
        parentCloudId: String,
        mirrorTombstones: Boolean = false
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (subtask.cloudId.isBlank() || parentCloudId.isBlank()) {
            return Result.failure(IllegalStateException("Subtask ${subtask.id} has no cloudId or parent"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val label = "subtask ${subtask.cloudId}"
                val payload = subtask.toFirestore(parentCloudId).toMap() + serverStamp()
                val docRef = subtasksRef(uid).document(subtask.cloudId)
                val pushed = pushGuarded(docRef, payload, subtask.updatedAt, label)
                logRefused(pushed, mirrorTombstones, label)
                Result.success(pushed)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync subtask to cloud")
                Result.failure(e)
            }
        }
    }

    fun pushSubtaskAsync(subtask: SubtaskEntity, parentCloudId: String) {
        scope.launch { syncSubtaskToCloud(subtask, parentCloudId) }
    }

    /**
     * Soft-deletes a subtask from Cloud Firestore by its stable cloudId.
     *
     * @return Result holding `true` when the tombstone was written.
     */
    suspend fun deleteSubtaskFromCloud(
        cloudId: String,
        deletedAt: Long = now()
    ): Result<Boolean> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        if (cloudId.isBlank()) return Result.success(true)
        return withContext(Dispatchers.IO) {
            try {
                Result.success(tombstone(subtasksRef(uid).document(cloudId), deletedAt, "subtask $cloudId"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete subtask from cloud")
                Result.failure(e)
            }
        }
    }

    fun deleteSubtaskAsync(cloudId: String, deletedAt: Long = now()) {
        scope.launch { deleteSubtaskFromCloud(cloudId, deletedAt) }
    }

    /** Fetches all cloud subtasks, TOMBSTONES INCLUDED (see [pullTasksFromCloud]). */
    suspend fun pullSubtasksFromCloud(): Result<List<CloudRow<FirestoreSubtask>>> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = subtasksRef(uid).get().await()
                Result.success(snapshot.toCloudRows { it.toObject(FirestoreSubtask::class.java) })
            } catch (e: Exception) {
                logFirestoreError("pull subtasks users/{uid}/subtasks", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Observes real-time changes to the cloud subtasks collection.
     *
     * Prefer this uid-pinned overload with a uid captured AFTER auth
     * completes.
     */
    fun observeCloudSubtasks(): Flow<List<CloudRow<FirestoreSubtask>>> =
        observeCloudSubtasksFor(currentUserId ?: "")

    fun observeCloudSubtasksFor(uid: String): Flow<List<CloudRow<FirestoreSubtask>>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = subtasksRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.e(error, "Firestore subtasks listener error")
                return@addSnapshotListener
            }
            trySend(snapshot?.toCloudRows { it.toObject(FirestoreSubtask::class.java) } ?: emptyList())
        }

        awaitClose { listener.remove() }
    }

    // ==========================================
    // FULL PUSH ALL
    // ==========================================

    /**
     * Pushes all local state (companion, tasks, reminders, subtasks, bond) to
     * Cloud Firestore in chunked batches, keyed by stable cloud ids.
     *
     * Tombstone-safe: a tombstoned (`deleted = true`) cloud document is never
     * overwritten by a plain push, and a local row that is missing from Room is
     * simply not written (absence is NOT interpreted as deletion — only an
     * explicit tombstone deletes anything).
     *
     * Batches cannot read, so the tombstone fields are compared in memory
     * against the state the caller observed earlier in [fullSync]
     * ([pullAndApplyCloud]); the per-entity guarded writes remain the
     * authoritative check for single-row pushes.
     *
     * @return Result holding the number of writes that were pushed.
     */
    suspend fun pushAllLocalToCloud(
        companion: Companion?,
        tasks: List<TaskEntity>,
        subtasks: List<SubtaskEntity> = emptyList(),
        reminders: List<ReminderEntity>,
        bond: Bond?,
        tombstonedIds: Set<String> = emptySet()
    ): Result<Int> {
        val uid = currentUserId ?: return Result.failure(IllegalStateException("No user logged in"))
        return withContext(Dispatchers.IO) {
            try {
                // Firestore batches are capped at 500 writes — chunk via chunked(400) to stay well under limit.
                val batchLimit = 400
                var batch = firestore.batch()
                var writes = 0
                var totalWrites = 0
                var skipped = 0
                suspend fun flushIfFull() {
                    if (writes >= batchLimit) {
                        batch.commit().await()
                        totalWrites += writes
                        batch = firestore.batch()
                        writes = 0
                    }
                }
                fun pushable(cloudId: String, updatedAt: Long): Boolean {
                    if (cloudId.isBlank()) return false
                    val blocked = cloudId in tombstonedIds &&
                        !SyncPolicy.canPushOverExisting(
                            cloudDeleted = true,
                            cloudEffective = 0L,
                            localUpdatedAt = updatedAt
                        )
                    if (blocked) skipped++
                    return !blocked
                }

                if (companion != null) {
                    val compRef = firestore.collection("users").document(uid).collection("companion").document("primary")
                    batch.set(compRef, companion.toFirestore().toMap() + serverStamp(), SetOptions.merge())
                    writes++
                    flushIfFull()
                }

                tasks.forEach { task ->
                    if (pushable(task.cloudId, task.updatedAt)) {
                        batch.set(
                            tasksRef(uid).document(task.cloudId),
                            task.toFirestore().toMap() + serverStamp(),
                            SetOptions.merge()
                        )
                        writes++
                        flushIfFull()
                    }
                }

                reminders.forEach { reminder ->
                    if (pushable(reminder.cloudId, reminder.updatedAt)) {
                        batch.set(
                            remindersRef(uid).document(reminder.cloudId),
                            reminder.toFirestore().toMap() + serverStamp(),
                            SetOptions.merge()
                        )
                        writes++
                        flushIfFull()
                    }
                }

                val taskCloudIdById = tasks.associate { it.id to it.cloudId }
                subtasks.forEach { subtask ->
                    val parentCloudId = taskCloudIdById[subtask.taskId]
                    if (!parentCloudId.isNullOrBlank() && pushable(subtask.cloudId, subtask.updatedAt)) {
                        batch.set(
                            subtasksRef(uid).document(subtask.cloudId),
                            subtask.toFirestore(parentCloudId).toMap() + serverStamp(),
                            SetOptions.merge()
                        )
                        writes++
                        flushIfFull()
                    }
                }

                if (bond != null) {
                    val bondRef = firestore.collection("users").document(uid).collection("metrics").document("bond")
                    batch.set(bondRef, bond.toFirestore().toMap() + serverStamp(), SetOptions.merge())
                    writes++
                }

                if (writes > 0) {
                    batch.commit().await()
                    totalWrites += writes
                }
                Timber.d(
                    "Pushed %d writes (%d tasks, %d subtasks, %d reminders; %d skipped as tombstoned) to Cloud Firestore",
                    totalWrites, tasks.size, subtasks.size, reminders.size, skipped
                )
                Result.success(totalWrites)
            } catch (e: Exception) {
                Timber.e(e, "Failed full local-to-cloud push")
                Result.failure(e)
            }
        }
    }
}

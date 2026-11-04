package com.pixelpal.app.data.remote.firebase

/**
 * Pure, I/O-free conflict-resolution rules for Cloud Firestore replication.
 *
 * Everything here is deliberately dependency-light (no `com.google.firebase.*`,
 * no `android.*`) so the LWW / tombstone / buffering rules can be unit-tested
 * on the JVM without a Firestore emulator or mocks.
 *
 * ## Timestamps
 * Two clocks are involved:
 *  - `updatedAt` — the writer's DEVICE wall clock (may be skewed, bumped by
 *    every local write).
 *  - `serverTs`   — Firestore's own `FieldValue.serverTimestamp()`, stamped by
 *    the server when the write commits (immune to device skew, but identical
 *    for two writers racing inside the same millisecond).
 *
 * The engine writes BOTH into the same map so a document has at most one
 * server timestamp. [lastWriterWins] compares `max(updatedAt, serverTs)`, which
 * lets a skewed-forward device eventually be corrected by a later server-stamped
 * write without ever losing the device's own ordering. A doc with no `serverTs`
 * (written by an older app version) falls back to plain `updatedAt`, and a doc
 * with no `updatedAt` (only [com.pixelpal.app.domain.model.CompanionRole] /
 * bond-style docs) falls back to `serverTs`.
 *
 * ## Deletes
 * [SyncPolicy.softDelete] makes a delete sticky: a `deleted = true` document
 * only ever yields to a strictly newer edit ([MergeAction.UPDATE]) and is never
 * resurrected by an ordinary write, and a tombstone is never TOMBSTONED twice.
 */
internal object SyncPolicy {

    /**
     * Firestore field carrying the server commit time (epoch millis).
     *
     * Deliberately NOT named after the data-class fields so a
     * `FieldValue.serverTimestamp()` written into an entity payload can never
     * collide with a `Long` field of the same name (Firestore rejects a
     * serverTimestamp written into a field the model declares as `Long`).
     */
    const val FIELD_SERVER_TS = "syncServerTs"

    // ---------------------------------------------------------------
    // Timestamps
    // ---------------------------------------------------------------

    /**
     * The effective ordering timestamp of a document.
     *
     * @param cloudUpdatedAt writer-side `updatedAt` (0/null-tolerant).
     * @param serverTsMillis Firestore [FIELD_SERVER_TS] in epoch millis, or null when
     *   the document predates server timestamps.
     */
    fun effectiveUpdatedAt(cloudUpdatedAt: Long, serverTsMillis: Long?): Long {
        val a = cloudUpdatedAt.coerceAtLeast(0L)
        val b = (serverTsMillis ?: 0L).coerceAtLeast(0L)
        return maxOf(a, b)
    }

    /**
     * True when the cloud copy should be written into Room.
     *
     * Strictly newer cloud wins; equal timestamps keep the local row (the local
     * write is the more recent user intent and is also backed by an explicit
     * local mutation).
     */
    fun shouldApplyRemote(cloudEffective: Long, localUpdatedAt: Long): Boolean =
        cloudEffective > localUpdatedAt.coerceAtLeast(0L)

    /**
     * The `updatedAt` a local row should adopt after applying a cloud row.
     *
     * Never lower than the local value: a cloud row stamped only by its
     * writer-side clock would otherwise move a server-stamped local row
     * backwards and make the next pull re-apply the same document forever.
     */
    fun localTimestampAfterApply(cloudEffective: Long, localUpdatedAt: Long): Long =
        maxOf(cloudEffective.coerceAtLeast(0L), localUpdatedAt.coerceAtLeast(0L))

    // ---------------------------------------------------------------
    // Tombstones
    // ---------------------------------------------------------------

    /** Outcome of merging one cloud document into the local mirror. */
    enum class MergeAction {
        /** Cloud is a tombstone and the local row is ours to delete. */
        TOMBSTONE,

        /** Cloud tombstone is older than a local edit: keep the local row. */
        KEEP_LOCAL,

        /** Cloud row is newer: insert/update the local row. */
        UPDATE,

        /** Local row is newer or equal: nothing to do. */
        SKIP
    }

    /**
     * Decide what to do with a remote document.
     *
     * @param cloudDeleted the document's tombstone flag.
     * @param cloudEffective [effectiveUpdatedAt] of the cloud document.
     * @param localExists whether a local row with that cloudId exists.
     * @param localUpdatedAt the local row's `updatedAt` (ignored when absent).
     */
    fun mergeAction(
        cloudDeleted: Boolean,
        cloudEffective: Long,
        localExists: Boolean,
        localUpdatedAt: Long
    ): MergeAction {
        if (!localExists) {
            // A tombstone is absorbed (nothing to delete) — crucial: inserting a
            // tombstoned row was the original resurrection bug.
            return if (cloudDeleted) MergeAction.SKIP else MergeAction.UPDATE
        }
        if (cloudDeleted) {
            return if (cloudEffective >= localUpdatedAt) MergeAction.TOMBSTONE else MergeAction.KEEP_LOCAL
        }
        return if (shouldApplyRemote(cloudEffective, localUpdatedAt)) MergeAction.UPDATE else MergeAction.SKIP
    }

    /**
     * Whether a local row may be pushed over the existing cloud document.
     *
     * A tombstone is only overwritten by a strictly newer edit — the same rule
     * that applies in [mergeAction] in the other direction. `cloudEffective` is
     * 0 when the document does not exist yet.
     */
    fun canPushOverExisting(cloudDeleted: Boolean, cloudEffective: Long, localUpdatedAt: Long): Boolean =
        !cloudDeleted || localUpdatedAt > cloudEffective

    /**
     * Decide whether a push-discovered tombstone must be mirrored locally.
     *
     * Pushing is how a device learns that a row it still holds was deleted
     * elsewhere; the tombstone then has to be applied to Room. Only applied
     * when the local row does not carry a newer edit.
     */
    fun shouldApplyPushTombstone(cloudEffective: Long, localUpdatedAt: Long): Boolean =
        cloudEffective >= localUpdatedAt

    // ---------------------------------------------------------------
    // Insert buffering (no primary companion yet)
    // ---------------------------------------------------------------

    /**
     * True when a cloud INSERT must be buffered because the row cannot be
     * attached yet: tasks require a companion FK, reminders require their parent
     * task (for subtasks) — inserts would either be dropped or orphaned.
     */
    fun needsBufferInsert(
        hasPrimaryCompanion: Boolean,
        parentRequired: Boolean,
        parentAvailable: Boolean
    ): Boolean = !hasPrimaryCompanion || (parentRequired && !parentAvailable)
}

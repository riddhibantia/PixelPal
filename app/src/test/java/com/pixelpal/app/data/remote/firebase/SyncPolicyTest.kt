package com.pixelpal.app.data.remote.firebase

import com.pixelpal.app.data.remote.firebase.SyncPolicy.MergeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock-free tests for the pure LWW / tombstone / buffering rules used by
 * [FirestoreSyncCoordinator] and [FirestoreSyncEngine].
 */
class SyncPolicyTest {

    // ---------------------------------------------------------------
    // effectiveUpdatedAt
    // ---------------------------------------------------------------

    @Test
    fun effectiveUpdatedAt_usesWriterClockWhenNoServerTimestamp() {
        assertEquals(1_000L, SyncPolicy.effectiveUpdatedAt(1_000L, null))
    }

    @Test
    fun effectiveUpdatedAt_takesLaterOfDeviceAndServerClock() {
        // Skewed-forward device: the writer clock is ahead, it still orders.
        assertEquals(5_000L, SyncPolicy.effectiveUpdatedAt(5_000L, 2_000L))
        // Server commit is later (device behind, or a peer wrote after us).
        assertEquals(9_000L, SyncPolicy.effectiveUpdatedAt(2_000L, 9_000L))
    }

    @Test
    fun effectiveUpdatedAt_neverTrustsNonPositiveValues() {
        assertEquals(0L, SyncPolicy.effectiveUpdatedAt(0L, null))
        assertEquals(0L, SyncPolicy.effectiveUpdatedAt(-5L, -9L))
        assertEquals(7L, SyncPolicy.effectiveUpdatedAt(-5L, 7L))
    }

    // ---------------------------------------------------------------
    // LWW
    // ---------------------------------------------------------------

    @Test
    fun shouldApplyRemote_onlyWhenCloudIsStrictlyNewer() {
        assertTrue(SyncPolicy.shouldApplyRemote(cloudEffective = 200L, localUpdatedAt = 100L))
        assertFalse(SyncPolicy.shouldApplyRemote(cloudEffective = 100L, localUpdatedAt = 100L))
        assertFalse(SyncPolicy.shouldApplyRemote(cloudEffective = 50L, localUpdatedAt = 100L))
    }

    @Test
    fun localTimestampAfterApply_neverMovesBackwards() {
        // Cloud doc older by writer clock but newer by server clock.
        assertEquals(500L, SyncPolicy.localTimestampAfterApply(cloudEffective = 500L, localUpdatedAt = 100L))
        // A server-stamped local row must not regress to a peer's older writer clock.
        assertEquals(900L, SyncPolicy.localTimestampAfterApply(cloudEffective = 300L, localUpdatedAt = 900L))
    }

    // ---------------------------------------------------------------
    // Tombstones — cloud → local
    // ---------------------------------------------------------------

    @Test
    fun mergeAction_tombstoneDeletesExistingLocalRow() {
        assertEquals(
            MergeAction.TOMBSTONE,
            SyncPolicy.mergeAction(cloudDeleted = true, cloudEffective = 100L, localExists = true, localUpdatedAt = 100L)
        )
        assertEquals(
            MergeAction.TOMBSTONE,
            SyncPolicy.mergeAction(cloudDeleted = true, cloudEffective = 200L, localExists = true, localUpdatedAt = 100L)
        )
    }

    @Test
    fun mergeAction_tombstoneIsIgnoredWhenLocalEditIsNewer() {
        assertEquals(
            MergeAction.KEEP_LOCAL,
            SyncPolicy.mergeAction(cloudDeleted = true, cloudEffective = 99L, localExists = true, localUpdatedAt = 100L)
        )
    }

    @Test
    fun mergeAction_tombstoneForUnknownRowDoesNothing() {
        // The original resurrection bug: a tombstoned doc must never be INSERTED.
        assertEquals(
            MergeAction.SKIP,
            SyncPolicy.mergeAction(cloudDeleted = true, cloudEffective = 100L, localExists = false, localUpdatedAt = 0L)
        )
    }

    @Test
    fun mergeAction_liveRemoteRowInsertsOrUpdates() {
        assertEquals(
            MergeAction.UPDATE,
            SyncPolicy.mergeAction(cloudDeleted = false, cloudEffective = 100L, localExists = false, localUpdatedAt = 0L)
        )
        assertEquals(
            MergeAction.UPDATE,
            SyncPolicy.mergeAction(cloudDeleted = false, cloudEffective = 200L, localExists = true, localUpdatedAt = 100L)
        )
        assertEquals(
            MergeAction.SKIP,
            SyncPolicy.mergeAction(cloudDeleted = false, cloudEffective = 100L, localExists = true, localUpdatedAt = 100L)
        )
    }

    // ---------------------------------------------------------------
    // Tombstones — local → cloud (push guards)
    // ---------------------------------------------------------------

    @Test
    fun canPushOverExisting_liveOrMissingCloudDocAlwaysAcceptPush() {
        assertTrue(SyncPolicy.canPushOverExisting(cloudDeleted = false, cloudEffective = 9_999L, localUpdatedAt = 1L))
        assertTrue(SyncPolicy.canPushOverExisting(cloudDeleted = false, cloudEffective = 0L, localUpdatedAt = 1L))
    }

    @Test
    fun canPushOverExisting_tombstoneOnlyYieldsToStrictlyNewerEdit() {
        // Stale local copy (e.g. queued offline push) must NOT resurrect.
        assertFalse(SyncPolicy.canPushOverExisting(cloudDeleted = true, cloudEffective = 100L, localUpdatedAt = 100L))
        assertFalse(SyncPolicy.canPushOverExisting(cloudDeleted = true, cloudEffective = 100L, localUpdatedAt = 50L))
        // A genuine re-save / undo after the delete wins.
        assertTrue(SyncPolicy.canPushOverExisting(cloudDeleted = true, cloudEffective = 100L, localUpdatedAt = 101L))
    }

    @Test
    fun shouldApplyPushTombstone_mirrorsWhenLocalIsNotNewer() {
        assertTrue(SyncPolicy.shouldApplyPushTombstone(cloudEffective = 100L, localUpdatedAt = 100L))
        assertTrue(SyncPolicy.shouldApplyPushTombstone(cloudEffective = 100L, localUpdatedAt = 10L))
        assertFalse(SyncPolicy.shouldApplyPushTombstone(cloudEffective = 100L, localUpdatedAt = 101L))
    }

    // ---------------------------------------------------------------
    // Insert buffering
    // ---------------------------------------------------------------

    @Test
    fun needsBufferInsert_withoutPrimaryCompanion() {
        assertTrue(
            SyncPolicy.needsBufferInsert(hasPrimaryCompanion = false, parentRequired = false, parentAvailable = false)
        )
    }

    @Test
    fun needsBufferInsert_whenParentTaskIsMissing() {
        assertTrue(
            SyncPolicy.needsBufferInsert(hasPrimaryCompanion = true, parentRequired = true, parentAvailable = false)
        )
    }

    @Test
    fun needsBufferInsert_falseWhenAnchorsExist() {
        assertFalse(
            SyncPolicy.needsBufferInsert(hasPrimaryCompanion = true, parentRequired = false, parentAvailable = false)
        )
        assertFalse(
            SyncPolicy.needsBufferInsert(hasPrimaryCompanion = true, parentRequired = true, parentAvailable = true)
        )
    }

    @Test
    fun serverTimestampField_doesNotCollideWithEntityFields() {
        // The wire field must not shadow a data-class property (Firestore rejects
        // a serverTimestamp written into a Long field).
        assertEquals("syncServerTs", SyncPolicy.FIELD_SERVER_TS)
        assertFalse(SyncPolicy.FIELD_SERVER_TS == "updatedAt")
        assertFalse(SyncPolicy.FIELD_SERVER_TS == "deleted")
    }
}

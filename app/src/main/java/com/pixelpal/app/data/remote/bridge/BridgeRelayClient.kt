package com.pixelpal.app.data.remote.bridge

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone half of the relay (see `tools/agent-bridge/pixelpal_bridge/relay.py`).
 *
 * Lets the pet work when the phone is **not** on the same Wi-Fi as the laptop.
 * The document layout is a shared contract; change both sides together.
 *
 * ```
 * relays/{lookupId}
 *   verifier, bridgeUid, phoneUid, lastSeen
 *   state/current  { agents: "<json>", updatedAt }
 *   inbox/current  { approvalId, decision }
 * ```
 */
@Singleton
class BridgeRelayClient @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {

    private val json = Json { ignoreUnknownKeys = true }

    data class Pairing(
        val lookupId: String,
        val relayKey: ByteArray
    ) {
        // ByteArray needs explicit equals/hashCode for data-class semantics.
        override fun equals(other: Any?): Boolean =
            other is Pairing && lookupId == other.lookupId &&
                relayKey.contentEquals(other.relayKey)

        override fun hashCode(): Int = 31 * lookupId.hashCode() + relayKey.contentHashCode()
    }

    sealed interface PairResult {
        /** Paired. [payload] is the bridge's current agents JSON, if published. */
        data class Success(val payload: String?) : PairResult
        data object WrongCode : PairResult
        data object BridgeOffline : PairResult
        data class Failed(val message: String) : PairResult
    }

    private fun relayDoc(lookupId: String) =
        firestore.collection("relays").document(lookupId)

    /**
     * Claim a relay with a pairing code typed by the user.
     *
     * Steps: derive the key locally → read the handshake → verify it → claim
     * `phoneUid` so the rules let us read state afterwards.
     */
    suspend fun pair(rawCode: String): PairResult {
        val normalized = RelayCrypto.normalizeCode(rawCode)
            ?: return PairResult.WrongCode
        val key = RelayCrypto.deriveRelayKey(normalized)
        val lookupId = RelayCrypto.lookupId(normalized)

        val snapshot: DocumentSnapshot = try {
            relayDoc(lookupId).get().await()
        } catch (e: Exception) {
            Timber.e(e, "Relay lookup failed")
            return PairResult.Failed(e.localizedMessage ?: "Could not reach the relay")
        }

        if (!snapshot.exists()) {
            // Either the code is wrong or setup hasn't run. Both are "no relay".
            return PairResult.BridgeOffline
        }

        val storedVerifier = snapshot.getString("verifier")
        if (!RelayCrypto.verify(key, storedVerifier)) {
            Timber.w("Relay verifier mismatch — wrong code")
            return PairResult.WrongCode
        }

        val uid = auth.currentUser?.uid
            ?: return PairResult.Failed("Not signed in")

        // Claim the relay (rules allow this exactly once, by us).
        try {
            relayDoc(lookupId).update("phoneUid", uid).await()
        } catch (e: Exception) {
            // Already claimed by this uid is fine; by someone else is not.
            val existing = try {
                relayDoc(lookupId).get().await().getString("phoneUid")
            } catch (inner: Exception) {
                null
            }
            if (existing != uid) {
                Timber.w(e, "Relay already claimed by another device")
                return PairResult.Failed("This code is already paired to another phone")
            }
        }

        val payload = try {
            fetchAgents(lookupId)
        } catch (e: Exception) {
            // A missing state doc just means no agent has reported yet.
            Timber.d(e, "No relay state published yet")
            null
        }
        return PairResult.Success(payload)
    }

    /** Read the bridge's current `/agents` payload (already JSON). */
    suspend fun fetchAgents(lookupId: String): String? {
        val doc = relayDoc(lookupId).collection("state").document("current").get().await()
        return doc.getString("agents")
    }

    /** Publish an approval verdict for the bridge to pick up. */
    suspend fun sendVerdict(lookupId: String, approvalId: String, approved: Boolean): Boolean =
        try {
            relayDoc(lookupId).collection("inbox").document("current")
                .set(
                    mapOf(
                        "approvalId" to approvalId,
                        "decision" to if (approved) "approve" else "deny",
                        "answeredAt" to System.currentTimeMillis()
                    )
                )
                .await()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to send relay verdict")
            false
        }

    /** Stop using a relay: release our claim so another device can pair. */
    suspend fun unpair(lookupId: String): Boolean =
        try {
            relayDoc(lookupId).update("phoneUid", null).await()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to release relay")
            false
        }

    /**
     * Parse a relay payload into the same wire type the direct transport uses,
     * so both transports feed [AgentSessionRepository] through one path.
     */
    fun parsePayload(payload: String?): List<BridgeRelayAgent> {
        if (payload.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<WireAgents>(payload).agents
        } catch (e: Exception) {
            Timber.e(e, "Malformed relay payload")
            emptyList()
        }
    }

    @kotlinx.serialization.Serializable
    private data class WireAgents(val agents: List<BridgeRelayAgent> = emptyList())

    /**
     * Mirrors the fields `AgentSessionRepository.WireAgent` reads. Kept separate
     * so the two transports can diverge without breaking either one.
     */
    @kotlinx.serialization.Serializable
    data class BridgeRelayAgent(
        val status: String = "IDLE",
        val message: String = "",
        val currentTask: String? = null,
        val source: String = "generic",
        val agentName: String = "",
        val sessionId: String = "default",
        val project: String = "",
        val lastEventAgeSeconds: Long? = null,
        val toolsCompleted: Int = 0,
        val pendingApproval: BridgeRelayApproval? = null
    )

    @kotlinx.serialization.Serializable
    data class BridgeRelayApproval(
        val id: String = "",
        val action: String = "",
        val detail: String? = null
    )
}

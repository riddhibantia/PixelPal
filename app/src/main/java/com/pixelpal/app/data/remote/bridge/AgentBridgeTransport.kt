package com.pixelpal.app.data.remote.bridge

import com.pixelpal.app.data.repository.AgentSessionRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches agent state from the bridge. This is the seam between the app and
 * however we reach the laptop.
 *
 * There used to be a second transport: a Firestore relay for when the phone
 * was away from the Wi-Fi. It was removed because it cost 17,280 document
 * reads per user per day against a 50,000/day free tier (so it stopped
 * working for everyone after ~2 users) and it required deploying security
 * rules before it would pair at all.
 *
 * Off-Wi-Fi now means **Tailscale**: a private network between the user's own
 * devices. It is free, needs no third party in the data path, has no quota,
 * and the bridge reports its tailnet address during `pixelpal-bridge setup`.
 * Both addresses are ordinary HTTPS URLs, so this class does not need to know
 * which one it was given.
 */
@Singleton
class AgentBridgeTransport @Inject constructor(
    private val repository: AgentSessionRepository
) {

    data class Snapshot(
        /** Agents the bridge reported. Empty is meaningful: "no agents running". */
        val agents: List<AgentSessionRepository.WireAgent>
    )

    /**
     * Fetch the current agent list, or null when the bridge did not answer.
     *
     * Null is deliberately distinct from an empty list: a laptop that is
     * asleep must leave the existing pet rows alone rather than erase them.
     */
    suspend fun refresh(bridgeUrl: String): Snapshot? {
        if (bridgeUrl.isBlank()) return null
        val agents = try {
            repository.fetchAgents(bridgeUrl)
        } catch (e: Exception) {
            Timber.d(e, "Bridge unreachable")
            return null
        } ?: return null
        return Snapshot(agents)
    }

    /** Validate a scanned pairing before the app stores its address and key. */
    suspend fun verifyPairing(bridgeUrl: String, pairingKey: String): Boolean =
        repository.fetchAgents(bridgeUrl, pairingKey) != null

    suspend fun verifyPairingDetailed(bridgeUrl: String, pairingKey: String): Pair<Boolean, String?> =
        repository.verifyPairingWithReason(bridgeUrl, pairingKey)

    /** Send an approve/deny decision back to the bridge. */
    suspend fun respondToApproval(
        bridgeUrl: String,
        approvalId: String,
        approved: Boolean
    ): Boolean {
        if (bridgeUrl.isBlank()) return false
        return try {
            repository.respondToApproval(bridgeUrl, approvalId, approved)
        } catch (e: Exception) {
            Timber.d(e, "Approval could not be sent")
            false
        }
    }
}

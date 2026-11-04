package com.pixelpal.app.data.remote.bridge

import com.pixelpal.app.data.repository.AgentSessionRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides **how** to reach the bridge, so the rest of the app never has to care.
 *
 * Order of preference:
 *  1. **Direct (LAN)** — fastest, no third party involved. Used whenever the
 *     phone can reach the laptop.
 *  2. **Relay (Firestore)** — used when direct fails, e.g. phone on mobile data.
 *
 * The fallback is silent: the UI shows "Connected", and [Transport] is only
 * surfaced in diagnostics. A user should never have to reason about which path
 * is live.
 */
@Singleton
class AgentBridgeTransport @Inject constructor(
    private val repository: AgentSessionRepository,
    private val relay: BridgeRelayClient
) {

    enum class Kind { DIRECT, RELAY }

    data class Result(
        val kind: Kind,
        val agentsFound: Int,
        val fromRelayPayload: String? = null
    )

    /**
     * Fetch agents, preferring the direct bridge.
     *
     * [bridgeUrl] is the LAN endpoint, [relayLookupId] the paired relay (both may
     * be blank if the user hasn't paired that way).
     */
    suspend fun refresh(
        bridgeUrl: String,
        relayLookupId: String?
    ): Result? {
        // 1. Direct. A LAN bridge answers in milliseconds when present.
        if (bridgeUrl.isNotBlank()) {
            val direct = try {
                repository.fetchAgents(bridgeUrl)
            } catch (e: Exception) {
                Timber.d(e, "Direct transport failed")
                null
            }
            if (direct != null) {
                return Result(Kind.DIRECT, direct.size)
            }
        }

        // 2. Relay.
        if (!relayLookupId.isNullOrBlank()) {
            val payload = try {
                relay.fetchAgents(relayLookupId)
            } catch (e: Exception) {
                Timber.d(e, "Relay transport failed")
                null
            }
            if (payload != null) {
                return Result(
                    kind = Kind.RELAY,
                    agentsFound = relay.parsePayload(payload).size,
                    fromRelayPayload = payload
                )
            }
        }

        return null
    }

    /** Answer an approval over whichever transport is live. */
    suspend fun respondToApproval(
        bridgeUrl: String,
        relayLookupId: String?,
        approvalId: String,
        approved: Boolean
    ): Boolean {
        if (bridgeUrl.isNotBlank()) {
            val ok = try {
                repository.respondToApproval(bridgeUrl, approvalId, approved)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }
        if (!relayLookupId.isNullOrBlank()) {
            return relay.sendVerdict(relayLookupId, approvalId, approved)
        }
        return false
    }
}

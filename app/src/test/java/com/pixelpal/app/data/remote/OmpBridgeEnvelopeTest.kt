package com.pixelpal.app.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract test between the on-disk OMP bridge (`tools/agent-bridge/omp_bridge.py`)
 * and the app's envelope parser.
 *
 * Each [body] below is the *exact* JSON the bridge emits — including the extra
 * informational keys (`project`, `sessionTitle`, `sessionAgeSeconds`) that
 * PixelPal does not model. Those keys MUST be ignored rather than rejected,
 * otherwise connecting a real agent would surface as "Unrecognized response".
 * If the bridge's output format changes, update the fixtures here.
 */
class OmpBridgeEnvelopeTest {

    private val connector = GenericHttpAgentConnector(OkHttpClient())

    /** verbatim output of `omp_bridge.py --once` on a live session */
    private val idleEnvelope = """
        {
          "status": "IDLE",
          "message": "interrupted — waiting for you",
          "pendingApproval": null,
          "sessionAgeSeconds": 39444,
          "project": "projects/turfzo/turf_booking_app",
          "sessionTitle": "BOOK: Rebuild owner bookings section UI",
          "currentTask": "BOOK: Rebuild owner bookings section UI"
        }
    """.trimIndent()

    @Test
    fun ompBridge_idleEnvelope_parsesWithExtraKeysIgnored() {
        val result = GenericHttpAgentConnector.parseForTest(connector, idleEnvelope)

        assertNotNull("bridge envelope must not be rejected", result)
        assertEquals("IDLE", result!!.state.id)
        assertEquals("interrupted — waiting for you", result.message)
        assertEquals("BOOK: Rebuild owner bookings section UI", result.currentTask)
        assertNull(result.pendingApproval)
        assertNull("bridge omits progress when unknown", result.progress)
    }

    @Test
    fun ompBridge_workingEnvelope_mapsToWorkingState() {
        val body = """
            {"status":"WORKING","message":"editing MainActivity.kt",
             "currentTask":"editing MainActivity.kt","progress":50,
             "pendingApproval":null,"project":"PixelPal","sessionAgeSeconds":2}
        """.trimIndent()

        val result = GenericHttpAgentConnector.parseForTest(connector, body)

        assertNotNull(result)
        assertEquals("WORKING", result!!.state.id)
        assertEquals("editing MainActivity.kt", result.currentTask)
        assertEquals(50, result.progress)
    }

    @Test
    fun ompBridge_approvalEnvelope_roundTripsThroughRecordedDecision() {
        val body = """
            {"status":"WAITING_FOR_INPUT","message":"waiting for your answer",
             "currentTask":"waiting for your answer","pendingApproval":
             {"id":"req-1","action":"run rm -rf build/","detail":"cleans stale artifacts"}}
        """.trimIndent()

        val result = GenericHttpAgentConnector.parseForTest(connector, body)

        assertNotNull(result)
        assertEquals("WAITING_FOR_INPUT", result!!.state.id)
        assertEquals("req-1", result.pendingApproval?.id)
        assertEquals("run rm -rf build/", result.pendingApproval?.action)
    }

    @Test
    fun ompBridge_errorEnvelope_surfacesAsErrorState() {
        val body = """{"status":"ERROR","message":"agent reported an error","pendingApproval":null}"""

        val result = GenericHttpAgentConnector.parseForTest(connector, body)

        assertNotNull(result)
        assertEquals("ERROR", result!!.state.id)
    }

    @Test
    fun ompBridge_stopReasonsMapAsDocumented() {
        // AgentState.fromId: FAILED->ERROR, STOPPED->IDLE (AGENTS.md contract)
        assertEquals(
            "ERROR",
            GenericHttpAgentConnector.parseForTest(connector, """{"status":"FAILED"}""")!!.state.id
        )
        assertEquals(
            "IDLE",
            GenericHttpAgentConnector.parseForTest(connector, """{"status":"STOPPED"}""")!!.state.id
        )
    }
}

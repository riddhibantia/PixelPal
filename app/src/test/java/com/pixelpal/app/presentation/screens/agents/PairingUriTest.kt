package com.pixelpal.app.presentation.screens.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing URI is the one place a QR code can reconfigure the app, so it
 * must reject anything malformed instead of half-applying it.
 */
class PairingUriTest {

    @Test
    fun `parses a well-formed pairing uri`() {
        val parsed = ConnectedAgentsViewModel.parsePairingUri(
            "pixelpal://pair?host=192.168.1.20&port=8787&key=abcdef123456"
        )
        assertEquals("http://192.168.1.20:8787", parsed?.url)
        assertEquals("abcdef123456", parsed?.key)
        assertEquals("192.168.1.20:8787", parsed?.bridgeId)
    }

    @Test
    fun `defaults port to 8787 when omitted`() {
        val parsed = ConnectedAgentsViewModel.parsePairingUri(
            "pixelpal://pair?host=10.0.0.5&key=abcdef123456"
        )
        assertEquals("http://10.0.0.5:8787", parsed?.url)
    }

    @Test
    fun `accepts uppercase scheme`() {
        val parsed = ConnectedAgentsViewModel.parsePairingUri(
            "PIXELPAL://PAIR?host=10.0.0.5&port=9000&key=abcdef123456"
        )
        assertEquals("http://10.0.0.5:9000", parsed?.url)
    }

    @Test
    fun `rejects a different pixelpal scheme`() {
        assertNull(
            ConnectedAgentsViewModel.parsePairingUri(
                "pixelpal://something?host=1.2.3.4&key=abcdef123456"
            )
        )
    }

    @Test
    fun `rejects a plain https url`() {
        assertNull(ConnectedAgentsViewModel.parsePairingUri("https://example.com"))
    }

    @Test
    fun `rejects when key is missing`() {
        assertNull(ConnectedAgentsViewModel.parsePairingUri("pixelpal://pair?host=1.2.3.4"))
    }

    @Test
    fun `rejects when host is missing`() {
        assertNull(ConnectedAgentsViewModel.parsePairingUri("pixelpal://pair?key=abcdef123456"))
    }

    @Test
    fun `rejects a trivially short key`() {
        assertNull(
            ConnectedAgentsViewModel.parsePairingUri(
                "pixelpal://pair?host=1.2.3.4&key=short"
            )
        )
    }

    @Test
    fun `rejects empty and whitespace input`() {
        assertNull(ConnectedAgentsViewModel.parsePairingUri(""))
        assertNull(ConnectedAgentsViewModel.parsePairingUri("   "))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        val parsed = ConnectedAgentsViewModel.parsePairingUri(
            "  pixelpal://pair?host=1.2.3.4&key=abcdef123456  "
        )
        assertEquals("http://1.2.3.4:8787", parsed?.url)
    }

    /**
     * The URI the bridge actually prints must parse — this is the contract
     * between `pixelpal-bridge pair` and the app.
     */
    @Test
    fun `parses the bridge generated uri format`() {
        val generated = "pixelpal://pair?v=1&key=xR3k-9_qZ&port=8787&host=10.126.105.182"
        val parsed = ConnectedAgentsViewModel.parsePairingUri(generated)
        assertEquals("http://10.126.105.182:8787", parsed?.url)
        assertEquals("xR3k-9_qZ", parsed?.key)
    }
}

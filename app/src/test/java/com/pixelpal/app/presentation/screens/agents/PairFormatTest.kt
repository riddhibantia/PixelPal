package com.pixelpal.app.presentation.screens.agents
import org.junit.Test
import org.junit.Assert.*
class PairFormatTest {
    @Test fun `setup output parses to the right url and key`() {
        val real = "pixelpal://pair?host=10.126.105.182&port=8789&key=85e9a2dd497d41e1cbaac32ca9f74eaaa2dcc2120a5a568b7cc403a230631c37"
        val p = ConnectedAgentsViewModel.parsePairingUri(real)
        assertNotNull(p)
        assertEquals("http://10.126.105.182:8789", p!!.url)
        assertEquals("85e9a2dd497d41e1cbaac32ca9f74eaaa2dcc2120a5a568b7cc403a230631c37", p.key)
    }
}

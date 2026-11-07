package com.pixelpal.app.presentation.screens.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatRelativeTimeTest {

    @Test
    fun `formats recent seconds as just now or seconds`() {
        assertEquals("just now", formatRelativeTime(0))
        assertEquals("just now", formatRelativeTime(4))
        assertEquals("5s ago", formatRelativeTime(5))
        assertEquals("59s ago", formatRelativeTime(59))
    }

    @Test
    fun `formats minutes`() {
        assertEquals("1m ago", formatRelativeTime(60))
        assertEquals("5m ago", formatRelativeTime(300))
        assertEquals("59m ago", formatRelativeTime(3599))
    }

    @Test
    fun `formats hours`() {
        assertEquals("1h ago", formatRelativeTime(3600))
        assertEquals("19h ago", formatRelativeTime(71560)) // user exact test case!
        assertEquals("23h ago", formatRelativeTime(86399))
    }

    @Test
    fun `formats days`() {
        assertEquals("1d ago", formatRelativeTime(86400))
        assertEquals("2d ago", formatRelativeTime(172800))
    }
}

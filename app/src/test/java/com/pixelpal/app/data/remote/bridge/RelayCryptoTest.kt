package com.pixelpal.app.data.remote.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language contract tests.
 *
 * `tools/agent-bridge/pixelpal_bridge/relay_crypto.py` and this Kotlin object
 * must derive BYTE-IDENTICAL values or pairing silently fails. The expected
 * strings below were produced by the Python implementation, so a change to
 * either side that breaks the contract fails here instead of in the field.
 */
class RelayCryptoTest {

    /**
     * Vectors captured from the Python implementation:
     *   python3 -c "from pixelpal_bridge import relay_crypto as rc; ..."
     * DO NOT regenerate these from Kotlin — that would make the test
     * self-fulfilling and stop it catching drift.
     */
    private data class Vector(
        val code: String,
        val lookupId: String,
        val verifier: String
    )

    private val vectors = listOf(
        Vector(
            code = "346789ABC",
            lookupId = "69fe2165a6a9e0655a4ebf9f1ad0ae48",
            verifier = "2cb2fae0f0bb1af0b43c85d37d0d3c846c614b622c63b0a8f096a08b4b990c98"
        ),
        Vector(
            code = "DEFGHJKMN",
            lookupId = "661bc4fae4b874497d7a00a70eac1506",
            verifier = "bf2316bf903327d65dfd98fdf8fb5f090b3a6baf4c04b929f1991a184a0ae4ad"
        ),
        Vector(
            code = "PQRTUVWXY",
            lookupId = "3606c0996c002f106a5c47b47e7546d8",
            verifier = "742266cdee645a060ed7fb2fd4cf6b0398fa7ad8e5ed9facc488944fbd84bfc8"
        )
    )

    @Test
    fun `lookup id matches the python implementation`() {
        vectors.forEach { vector ->
            assertEquals(
                "lookupId drift for ${vector.code}",
                vector.lookupId,
                RelayCrypto.lookupId(vector.code)
            )
        }
    }

    @Test
    fun `verifier matches the python implementation`() {
        vectors.forEach { vector ->
            val key = RelayCrypto.deriveRelayKey(vector.code)
            assertEquals(
                "verifier drift for ${vector.code}",
                vector.verifier,
                RelayCrypto.verifier(key)
            )
        }
    }

    @Test
    fun `derived key is 32 bytes`() {
        assertEquals(32, RelayCrypto.deriveRelayKey("346789ABC").size)
    }

    @Test
    fun `alphabet has no ambiguous glyphs`() {
        "01OIL2Z5S".forEach { bad ->
            assertFalse("'$bad' is ambiguous", RelayCrypto.ALPHABET.contains(bad))
        }
    }

    @Test
    fun `alphabet symbols are unique`() {
        assertEquals(
            RelayCrypto.ALPHABET.length,
            RelayCrypto.ALPHABET.toSet().size
        )
    }

    @Test
    fun `normalizes lowercase spaces and dashes`() {
        assertEquals("346789ABC", RelayCrypto.normalizeCode("346-789-abc"))
        assertEquals("346789ABC", RelayCrypto.normalizeCode("346 789 abc"))
        assertEquals("346789ABC", RelayCrypto.normalizeCode("  346789ABC  "))
    }

    @Test
    fun `rejects malformed codes`() {
        assertNull(RelayCrypto.normalizeCode(null))
        assertNull(RelayCrypto.normalizeCode(""))
        assertNull(RelayCrypto.normalizeCode("   "))
        assertNull(RelayCrypto.normalizeCode("SHORT"))
        assertNull(RelayCrypto.normalizeCode("346789ABCDEFG"))
    }

    @Test
    fun `rejects ambiguous glyphs rather than silently rewriting them`() {
        // A typed 'O' is a real transcription error; guessing would pair with
        // the wrong key and fail confusingly later.
        assertNull(RelayCrypto.normalizeCode("OOOOOOOOO"))
        assertNull(RelayCrypto.normalizeCode("000000000"))
        assertNull(RelayCrypto.normalizeCode("IIIIIIIII"))
    }

    @Test
    fun `verify accepts the matching key and rejects a wrong one`() {
        val right = RelayCrypto.deriveRelayKey("346789ABC")
        val wrong = RelayCrypto.deriveRelayKey("DEFGHJKMN")
        assertTrue(RelayCrypto.verify(right, RelayCrypto.verifier(right)))
        assertFalse(RelayCrypto.verify(wrong, RelayCrypto.verifier(right)))
    }

    @Test
    fun `verify rejects blank and malformed verifiers`() {
        val key = RelayCrypto.deriveRelayKey("346789ABC")
        assertFalse(RelayCrypto.verify(key, null))
        assertFalse(RelayCrypto.verify(key, ""))
        assertFalse(RelayCrypto.verify(key, "deadbeef"))
    }

    @Test
    fun `verify tolerates upper case verifiers from the wire`() {
        val key = RelayCrypto.deriveRelayKey("346789ABC")
        assertTrue(RelayCrypto.verify(key, RelayCrypto.verifier(key).uppercase()))
    }

    @Test
    fun `format round trips through normalize`() {
        val normalized = RelayCrypto.normalizeCode("346789ABC")
        assertNotNull(normalized)
        assertEquals("346-789-ABC", RelayCrypto.formatCode(normalized!!))
        assertEquals(normalized, RelayCrypto.normalizeCode(RelayCrypto.formatCode(normalized)))
    }

    @Test
    fun `generated codes are always valid and grouped`() {
        repeat(100) {
            val code = RelayCrypto.generateCode()
            assertNotNull("generated code must normalize: $code", RelayCrypto.normalizeCode(code))
            assertEquals(2, code.count { it == '-' })
        }
    }

    @Test
    fun `different codes never produce the same lookup id`() {
        val ids = (1..100).map {
            RelayCrypto.lookupId(RelayCrypto.normalizeCode(RelayCrypto.generateCode())!!)
        }
        assertEquals(100, ids.toSet().size)
    }

    @Test
    fun `lookup id does not contain the code`() {
        val normalized = RelayCrypto.normalizeCode("346789ABC")!!
        assertFalse(RelayCrypto.lookupId(normalized).uppercase().contains(normalized))
    }

    @Test
    fun `verifier does not equal or contain the raw key`() {
        val key = RelayCrypto.deriveRelayKey("346789ABC")
        val verifier = RelayCrypto.verifier(key)
        val keyHex = key.joinToString("") { "%02x".format(it) }
        assertFalse(verifier == keyHex)
        assertFalse(verifier.contains(keyHex))
    }
}

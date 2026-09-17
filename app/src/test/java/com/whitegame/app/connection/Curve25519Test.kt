package com.whitegame.app.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Curve25519 is the one piece of hand-carried crypto in the app: if it drifts, generated
 * keypairs silently stop matching what the server was given. These are the published vectors.
 */
class Curve25519Test {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }

    /** RFC 7748 section 6.1 — Alice's key pair. */
    @Test
    fun derivesRfc7748PublicKey() {
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pub = Curve25519.scalarBaseMult(priv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(pub))
    }

    /** RFC 7748 section 6.1 — Bob's key pair. */
    @Test
    fun derivesSecondRfc7748PublicKey() {
        val priv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val pub = Curve25519.scalarBaseMult(priv)
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hex(pub))
    }

    /** RFC 7748 section 6.1 — both sides reach the same shared secret. */
    @Test
    fun agreesOnSharedSecret() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, hex(Curve25519.scalarMult(alicePriv, Curve25519.scalarBaseMult(bobPriv))))
        assertEquals(expected, hex(Curve25519.scalarMult(bobPriv, Curve25519.scalarBaseMult(alicePriv))))
    }
}

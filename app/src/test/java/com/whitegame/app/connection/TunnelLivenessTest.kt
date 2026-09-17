package com.whitegame.app.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the app throws someone off a tunnel. Getting it wrong the
 * lenient way costs a few seconds on a real outage; getting it wrong the eager way drops
 * players mid-match, which is what a stale-handshake-only check used to do.
 */
class TunnelLivenessTest {

    private val dead = 45_000L
    private fun now() = System.currentTimeMillis() / 1000

    private fun stats(handshakeAgeSec: Long, rx: Long = 1024) =
        TunnelStats(rxBytes = rx, txBytes = rx, lastHandshakeSec = now() - handshakeAgeSec)

    @Test fun `fresh handshake is alive whatever the traffic`() {
        assertFalse(stats(10).isPathDead(quietMs = 600_000, deadAfterMs = dead))
    }

    @Test fun `stale handshake with bytes still arriving is alive`() {
        // Idle-but-usable tunnel: no rekey for minutes, data flowed a second ago.
        assertFalse(stats(600).isPathDead(quietMs = 1_000, deadAfterMs = dead))
    }

    @Test fun `stale handshake and nothing received is dead`() {
        assertTrue(stats(600).isPathDead(quietMs = 46_000, deadAfterMs = dead))
    }

    @Test fun `a tunnel that never handshook is connecting, not dead`() {
        val never = TunnelStats(lastHandshakeSec = 0)
        assertFalse(never.isPathDead(quietMs = 600_000, deadAfterMs = dead))
    }

    /** VpnService.Builder throws on anything that is not a literal, so this gate matters. */
    @Test fun `dns override only accepts ip literals`() {
        assertTrue(TunnelController.isIpLiteral("1.1.1.1"))
        assertTrue(TunnelController.isIpLiteral("178.22.122.100"))
        assertTrue(TunnelController.isIpLiteral("2606:4700:4700::1111"))
        assertFalse(TunnelController.isIpLiteral("dns.google"))
        assertFalse(TunnelController.isIpLiteral("1.1.1"))
        assertFalse(TunnelController.isIpLiteral("999.1.1.1"))
        assertFalse(TunnelController.isIpLiteral(""))
    }
}

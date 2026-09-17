package com.whitegame.app.data

import org.junit.Assert.*
import org.junit.Test

class PlayStoreTest {
    @Test fun navigationGamesLinkDoesNotClassifyANonGame() {
        val html = """<meta property="og:title" content="Messenger - Apps on Google Play"><a href="/store/apps/category/GAME">Games</a><a href="/store/apps/category/COMMUNICATION">Communication</a>"""
        assertFalse(PlayStore.parse("com.example.chat", html)!!.isGame)
    }
    @Test fun gameGenreAndPublisherSiteAreRead() {
        val html = """<meta property="og:title" content="Race &amp; Win - Apps on Google Play"><a href="/store/apps/category/GAME_RACING">Racing</a><a href="mailto:help@studio.example">Support</a>"""
        val info = PlayStore.parse("com.example.game", html)!!
        assertTrue(info.isGame)
        assertEquals("Race & Win", info.title)
        assertEquals(listOf("studio.example"), info.hosts)
    }
    @Test fun unreachableRouteNeverBeatsReliableRoute() {
        assertTrue(DnsData.distanceScore(null, 100, null) > DnsData.distanceScore(100, 0, 10))
        assertTrue(DnsData.distanceScore(10, 50, 0) > DnsData.distanceScore(100, 0, 10))
    }
}

package com.whitegame.app.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameComparisonTest {
    @Test fun failedFirstHostFallsBackAndEveryRouteUsesTheSelectedHost() = runBlocking {
        val checked = mutableListOf<String>()
        val selected = GameComparison.selectEndpoint(listOf("retired.invalid", "working.example", "unused.example")) { host ->
            checked += host
            if (host == "working.example") listOf(RouteProbe.Result(ms = 120), RouteProbe.Result(ms = 90))
            else listOf(RouteProbe.Result(failure = RouteProbe.Failure.DNS), RouteProbe.Result(failure = RouteProbe.Failure.DNS))
        }
        assertEquals("working.example", selected.host)
        assertEquals(listOf("retired.invalid", "working.example"), checked)
        assertEquals(listOf(120L, 90L), selected.results.map { it.ms })
    }

    @Test fun proxySuccessIsEnoughToSelectEndpointWhenDirectRouteIsBlocked() = runBlocking {
        val selected = GameComparison.selectEndpoint(listOf("blocked-direct.example", "unused.example")) {
            listOf(RouteProbe.Result(failure = RouteProbe.Failure.CONNECT), RouteProbe.Result(ms = 150))
        }
        assertEquals("blocked-direct.example", selected.host)
        assertEquals(1, selected.checked)
    }

    @Test fun allEndpointsFailWithoutInventingSuccessfulSamples() = runBlocking {
        val selected = GameComparison.selectEndpoint(listOf("one", "one", "two", "three", "four", "five")) {
            listOf(RouteProbe.Result(failure = RouteProbe.Failure.TLS))
        }
        assertEquals("one", selected.host)
        assertEquals(4, selected.checked)
        assertNull(selected.results.single().ms)
        assertEquals(RouteProbe.Failure.TLS, selected.results.single().failure)
    }
}

package com.whitegame.app.data

import com.whitegame.app.model.DnsItem
import org.junit.Assert.*
import org.junit.Test

class DnsCandidatesTest {
    @Test fun boundedQuickScanIncludesEveryRequestedResolverAndKeepsOtherEntries() {
        val local = (1..35).map { DnsItem(it, "10.0.0.$it", "Local", "") }
        val public = DnsData.preferredScanIps.mapIndexed { i, ip -> DnsItem(i + 36, ip, "Public", "") }
        val candidates = DnsData.scanCandidates(local + public + public)
        assertEquals(30, candidates.size)
        assertTrue(candidates.map { it.ip }.containsAll(DnsData.preferredScanIps))
        assertEquals(30, candidates.map { it.ip }.distinct().size)
        assertTrue(candidates.any { it.ip == "10.0.0.1" })
    }
}

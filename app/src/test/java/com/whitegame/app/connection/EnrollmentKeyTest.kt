package com.whitegame.app.connection

import org.junit.Assert.*
import org.junit.Test

class EnrollmentKeyTest {
    private val key = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="

    @Test fun retriesAndNewSessionsReusePersistedKey() {
        var persisted: String? = null
        var generations = 0
        val first = EnrollmentKey.loadOrCreate({ persisted }, { persisted = it; true }) { generations++; key }
        val retry = EnrollmentKey.loadOrCreate({ persisted }, { error("Must not write another key") }) { error("Must not generate another key") }
        assertEquals(first, retry)
        assertEquals(1, generations)
    }

    @Test fun failureToPersistStopsBeforeEnrollment() {
        assertTrue(runCatching { EnrollmentKey.loadOrCreate({ null }, { false }) { key } }.exceptionOrNull() is ConfigException)
    }

    @Test fun corruptPersistedKeyIsNeverSilentlyReplaced() {
        assertTrue(runCatching { EnrollmentKey.loadOrCreate({ "corrupt" }, { error("Must not replace existing identity") }) { key } }
            .exceptionOrNull() is ConfigException)
    }
}

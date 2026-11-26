package com.whitegame.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LinuxNetworkHelperTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun dnsOverrideIsRestoredWhenOwnerClosesPipe() = verifyDnsSession("192.0.2.53", null, "192.0.2.53")

    @Test
    fun dnsChangedByAnotherProgramIsPreserved() = verifyDnsSession("192.0.2.53", "192.0.2.54", "192.0.2.54")

    @Test
    fun missingLinkDnsReturnsToAnEmptySetting() = verifyDnsSession("", null, "")

    private fun verifyDnsSession(initial: String, externalChange: String?, expected: String) {
        assumeTrue(DesktopPlatform.linux)
        val source = checkNotNull(javaClass.getResourceAsStream("/whitegame/LinuxNetwork.sh"))
            .bufferedReader().use { it.readText() }
        val state = temporary.newFile("dns-state").toPath()
        Files.writeString(state, "$initial\n")
        val mock = temporary.newFile("resolvectl").toPath()
        Files.writeString(mock, """
            #!/bin/bash
            set -eu
            [[ "$1" == dns && "$2" == eth0 ]]
            shift 2
            if (( $# == 0 )); then
                printf 'Link 2 (eth0): '
                cat "${'$'}DNS_STATE"
            else
                printf '%s\n' "$*" > "${'$'}DNS_STATE"
            fi
        """.trimIndent())
        assertTrue(mock.toFile().setExecutable(true))
        val process = ProcessBuilder("/bin/bash", "-c", source, "whitegame", "dns",
            mock.toString(), "eth0", "198.51.100.53")
            .redirectError(ProcessBuilder.Redirect.INHERIT).apply {
                environment()["DNS_STATE"] = state.toString()
            }.start()
        try {
            val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readLine() } }
            assertEquals("READY", ready.get(5, TimeUnit.SECONDS))
            assertEquals("198.51.100.53", Files.readString(state).trim())
            if (externalChange != null) Files.writeString(state, "$externalChange\n")
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(expected, Files.readString(state).trim())
        } finally {
            process.outputStream.close()
            if (process.isAlive) process.destroy()
        }
    }
}

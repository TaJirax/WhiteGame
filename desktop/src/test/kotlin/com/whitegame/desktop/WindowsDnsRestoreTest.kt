package com.whitegame.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class WindowsDnsRestoreTest {
    @Test
    fun restoresDhcpAndStaticResolversWithoutOverwritingIndependentChanges() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("whitegame-dns-restore-")
        val helper = directory.resolve("WindowsNetwork.ps1")
        val snapshot = directory.resolve("dns-snapshot.json")
        try {
            requireNotNull(javaClass.getResourceAsStream("/whitegame/WindowsNetwork.ps1")).use { Files.copy(it, helper) }
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}ProgressPreference = 'SilentlyContinue'
                ${'$'}directory = '${directory.toString().replace("'", "''")}'
                ${'$'}dnsPath = Join-Path ${'$'}directory 'dns-snapshot.json'
                ${'$'}encoding = [Text.UTF8Encoding]::new(${'$'}false)
                ${'$'}tokens = ${'$'}null
                ${'$'}errors = ${'$'}null
                ${'$'}ast = [Management.Automation.Language.Parser]::ParseFile(
                    (Join-Path ${'$'}directory 'WindowsNetwork.ps1'), [ref]${'$'}tokens, [ref]${'$'}errors)
                if (${'$'}errors.Count) { throw ${'$'}errors[0] }
                foreach (${'$'}function in ${'$'}ast.FindAll({
                    param(${'$'}node)
                    ${'$'}node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                        ${'$'}node.Name -in @('Dns-ServerList', 'Restore-Dns')
                }, ${'$'}false)) {
                    . ([ScriptBlock]::Create(${'$'}function.Extent.Text))
                }

                ${'$'}a = '87de4481-7a0c-4554-a33d-22021943cd18'
                ${'$'}b = 'b6b0af2a-12b7-470d-94e5-2025312a1aac'
                ${'$'}c = 'c4fdc859-34ac-4eb2-a794-a28ed9753de6'
                ${'$'}script:effective = @{ 10 = @('1.1.1.1', '1.0.0.1'); 11 = @('2606:4700:4700::1111'); 12 = @('9.9.9.9') }
                ${'$'}script:configured = @{ 10 = ${'$'}effective[10]; 11 = ${'$'}effective[11]; 12 = ${'$'}effective[12] }
                ${'$'}script:requests = @()
                ${'$'}rows = @(
                    @{ guid = ${'$'}a; family = 'IPv4'; applied = ${'$'}effective[10]; original = @() },
                    @{ guid = ${'$'}b; family = 'IPv6'; applied = ${'$'}effective[11]; original = @('2620:fe::fe') },
                    @{ guid = ${'$'}c; family = 'IPv4'; applied = @('1.1.1.1'); original = @() }
                )
                [IO.File]::WriteAllText(${'$'}dnsPath, (ConvertTo-Json -InputObject ${'$'}rows -Depth 4), ${'$'}encoding)
                function Get-NetAdapter([switch]${'$'}IncludeHidden) {
                    [PSCustomObject]@{ InterfaceGuid = ${'$'}a; ifIndex = 10 }
                    [PSCustomObject]@{ InterfaceGuid = ${'$'}b; ifIndex = 11 }
                    [PSCustomObject]@{ InterfaceGuid = ${'$'}c; ifIndex = 12 }
                }
                function Get-DnsClientServerAddress([int]${'$'}InterfaceIndex, ${'$'}AddressFamily) {
                    [PSCustomObject]@{ Index = ${'$'}InterfaceIndex; ServerAddresses = ${'$'}effective[${'$'}InterfaceIndex] }
                }
                function Set-DnsClientServerAddress(${'$'}InputObject, [string[]]${'$'}ServerAddresses, [switch]${'$'}ResetServerAddresses) {
                    ${'$'}index = ${'$'}InputObject.Index
                    ${'$'}script:requests += ${'$'}index
                    if (${'$'}ResetServerAddresses) {
                        ${'$'}script:configured[${'$'}index] = @()
                        ${'$'}script:effective[${'$'}index] = @('192.0.2.53')
                    } else {
                        ${'$'}script:configured[${'$'}index] = ${'$'}ServerAddresses
                        ${'$'}script:effective[${'$'}index] = ${'$'}ServerAddresses
                    }
                }
                function Read-StaticDns(${'$'}guid, ${'$'}family) {
                    ${'$'}entry = Get-NetAdapter | Where-Object InterfaceGuid -eq ${'$'}guid
                    Dns-ServerList ${'$'}script:configured[${'$'}entry.ifIndex]
                }
                function Clear-DnsClientCache {}

                Restore-Dns
                @{ calls = ${'$'}requests; dhcp = ${'$'}effective[10][0]; static = ${'$'}effective[11][0]; independent = ${'$'}effective[12][0] } |
                    ConvertTo-Json -Compress
            """.trimIndent()
            val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", command)
                .redirectErrorStream(true).start()
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            val result = try {
                assertTrue("Windows PowerShell timed out.", process.waitFor(15, TimeUnit.SECONDS))
                val report = output.get(2, TimeUnit.SECONDS)
                assertEquals(report, 0, process.exitValue())
                val json = report.lineSequence().map(String::trim).lastOrNull { it.startsWith('{') }
                assertTrue(report, json != null)
                JSONObject(json)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            assertEquals("[10,11]", result.getJSONArray("calls").toString())
            assertEquals("192.0.2.53", result.getString("dhcp"))
            assertEquals(java.net.InetAddress.getByName("2620:fe::fe"), java.net.InetAddress.getByName(result.getString("static")))
            assertEquals("9.9.9.9", result.getString("independent"))
        } finally {
            Files.deleteIfExists(snapshot)
            Files.deleteIfExists(helper)
            Files.deleteIfExists(directory)
        }
    }
}

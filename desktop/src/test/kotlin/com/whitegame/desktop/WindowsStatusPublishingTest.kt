package com.whitegame.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class WindowsStatusPublishingTest {
    @Test
    fun windowsPowerShellCanReplaceTheInitialStatus() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("whitegame-status-test-")
        val resource = directory.resolve("WindowsNetwork.ps1")
        val status = directory.resolve("status.json")
        try {
            requireNotNull(javaClass.getResourceAsStream("/whitegame/WindowsNetwork.ps1")).use {
                Files.copy(it, resource)
            }
            // Load only the production publisher function. No networking code
            // or elevated Windows process should run in this unit test.
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}session = '${directory.toString().replace("'", "''")}'
                ${'$'}statePath = Join-Path ${'$'}session 'status.json'
                ${'$'}encoding = [Text.UTF8Encoding]::new(${'$'}false)
                ${'$'}tokens = ${'$'}null
                ${'$'}errors = ${'$'}null
                ${'$'}ast = [Management.Automation.Language.Parser]::ParseFile(
                    (Join-Path ${'$'}session 'WindowsNetwork.ps1'), [ref]${'$'}tokens, [ref]${'$'}errors)
                if (${'$'}errors.Count) { throw ${'$'}errors[0] }
                ${'$'}writer = ${'$'}ast.Find({
                    param(${'$'}node)
                    ${'$'}node -is [Management.Automation.Language.FunctionDefinitionAst] -and ${'$'}node.Name -eq 'Write-Status'
                }, ${'$'}false)
                . ([ScriptBlock]::Create(${'$'}writer.Extent.Text))
                Write-Status 'starting' 'Starting'
                Write-Status 'running' 'Ready' @{rx=120}
            """.trimIndent()
            val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", command)
                .redirectErrorStream(true).start()
            try {
                assertTrue("Windows PowerShell timed out.", process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().use { it.readText() }, 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val result = JSONObject(Files.readString(status))
            assertEquals("running", result.getString("phase"))
            assertEquals(120, result.getInt("rx"))
        } finally {
            Files.deleteIfExists(status)
            Files.deleteIfExists(resource)
            Files.deleteIfExists(directory)
        }
    }
}

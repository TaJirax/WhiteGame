package com.whitegame.desktop

import com.whitegame.app.connection.Keys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses the production WindowsTunnels API. The launcher only enables it inside
 * a GitHub-hosted Windows runner or an explicitly confirmed disposable VM.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WindowsNetworkIntegrationTest {
    @Before
    fun requireDisposableWindows() {
        assumeTrue("Run desktop/windows-integration.ps1 on a disposable VM.", System.getenv("WHITEGAME_WINDOWS_LIVE_SMOKE") == "1")
        check(System.getProperty("os.name").startsWith("Windows")) { "A Windows VM is required." }
        check(
            (System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_ENVIRONMENT") == "github-hosted") ||
                System.getenv("WHITEGAME_DISPOSABLE_VM") == "1"
        ) { "A disposable Windows VM must be explicitly selected." }
    }

    @Test(timeout = 160_000)
    fun dnsOverrideOnActiveAdaptersRestoresOriginalSettings() = withNetwork { _, client ->
        val original = dnsSettings()
        assertTrue("The Windows VM has no active IPv4 default-route adapter.", original.isNotEmpty())
        val primary = original.flatMap { it.servers }.firstOrNull()
            ?: error("The Windows VM has no existing IPv4 resolver to keep online.")
        val extra = listOf("1.1.1.1", "9.9.9.9").first { it != primary }
        val override = listOf(primary, extra)
        assertTrue("Choose a fixture list different from the baseline.", original.any { it.servers != override })

        try {
            client.applyDns(override)
            assertEquals(override, client.currentDns())
            val applied = dnsSettings().associateBy(DnsSetting::guid)
            original.forEach { old ->
                assertEquals("DNS override for ${old.guid}", override, applied[old.guid]?.servers)
            }
        } finally {
            client.restoreDns()
        }
        assertEquals(emptyList<String>(), client.currentDns())
        assertEquals("Configured and effective DNS should both return to baseline.", original, dnsSettings())
    }

    @Test(timeout = 170_000)
    fun wireGuardDriverCarriesAuthenticatedPayloadAndRemovesService() = withNetwork { root, client ->
        verifyWireGuardTunnel(root, client, "10.203.218.1", "10.203.218.2", "10.203.218.2/32", setOf("10.203.218.2/32"))
    }

    @Test(timeout = 240_000)
    fun wireGuardFullIpv4RouteCarriesAuthenticatedPayloadAndRestoresRoutes() = withNetwork { root, client ->
        withIpv4RouteGuards {
            verifyWireGuardTunnel(root, client, "198.18.32.1", "198.18.32.2", "0.0.0.0/0", setOf("0.0.0.0/1", "128.0.0.0/1"))
        }
    }

    private fun verifyWireGuardTunnel(
        root: Path, client: WindowsTunnels, local: String, remote: String, allowedIps: String, tunnelRoutes: Set<String>
    ) {
        // The remote address belongs only to the userspace peer. A direct
        // Windows connection to it could not satisfy the random echo check.
        val routesBefore = activeRoutes(tunnelRoutes)
        val defaultsBefore = defaultRoutes()
        val clientKey = Keys.generatePrivateKey()
        val peerKey = Keys.generatePrivateKey()
        val executable = checkNotNull(System.getenv("WHITEGAME_WIREGUARD_TEST_PEER"))
        val fixture = ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var adapter: String? = null
        try {
            val input = JSONObject().put("PrivateKey", Keys.toHex(peerKey))
                .put("ClientKey", Keys.toHex(Keys.publicKeyOf(clientKey)))
                .put("ServerIP", remote).put("ClientIP", local)
            fixture.outputStream.write((input.toString() + "\n").toByteArray())
            fixture.outputStream.flush()
            val ready = CompletableFuture.supplyAsync { fixture.inputStream.bufferedReader().readLine() }
                .get(15, TimeUnit.SECONDS).orEmpty().split(' ')
            check(ready.size == 3 && ready[0] == "READY") { "The userspace WireGuard fixture failed to start." }
            val config = """
                [Interface]
                PrivateKey = $clientKey
                Address = $local/32

                [Peer]
                PublicKey = ${Keys.publicKeyOf(peerKey)}
                AllowedIPs = $allowedIps
                Endpoint = 127.0.0.1:${ready[1].toInt()}
            """.trimIndent()
            AutoCloseable(client::disconnect).use {
                client.connect("Windows WireGuard integration", config, killSwitch = false, autoReconnect = false)
                val activeAdapter = tunnelAdapter(root)
                adapter = activeAdapter
                assertTrue(client.status().connected)
                await("authenticated WireGuard echo") { matchesEcho(remote, ready[2].toInt()) }
                val installedRoutes = powerShell("""
                    ${'$'}index = (Get-NetAdapter -Name '$activeAdapter' -IncludeHidden).InterfaceIndex
                    Get-NetRoute -InterfaceIndex ${'$'}index -PolicyStore ActiveStore -AddressFamily IPv4 |
                        Select-Object -ExpandProperty DestinationPrefix
                """.trimIndent()).lineSequence().toSet()
                assertTrue("The native adapter must install $tunnelRoutes.", installedRoutes.containsAll(tunnelRoutes))
                val chosenRoute = powerShell("""
                    ${'$'}index = (Get-NetAdapter -Name '$activeAdapter' -IncludeHidden).InterfaceIndex
                    Find-NetRoute -RemoteIPAddress '$remote' |
                        Where-Object { ${'$'}_.DestinationPrefix -and ${'$'}_.InterfaceIndex -eq ${'$'}index } |
                        Select-Object -ExpandProperty DestinationPrefix
                """.trimIndent())
                assertTrue("The echo must use the advertised tunnel route.", chosenRoute in tunnelRoutes)
                await("WireGuard handshake and bidirectional counters") {
                    val status = client.status()
                    status.lastHandshakeSeconds != null && (status.rxBytes ?: 0L) > 0 && (status.txBytes ?: 0L) > 0
                }
                val service = "'WireGuardTunnel$" + adapter + "'"
                assertEquals("yes", powerShell("if (Get-Service -Name $service -ErrorAction SilentlyContinue) { 'yes' }"))
            }
        } finally {
            fixture.outputStream.close()
            if (!fixture.waitFor(5, TimeUnit.SECONDS)) fixture.destroyForcibly()
        }
        assertFalse(client.status().connected)
        val service = "'WireGuardTunnel$" + checkNotNull(adapter) + "'"
        assertEquals("gone", powerShell("if (!(Get-Service -Name $service -ErrorAction SilentlyContinue)) { 'gone' }"))
        assertEquals("The native service must restore its tunnel destinations.", routesBefore, activeRoutes(tunnelRoutes))
        assertEquals("The original default routes must survive the tunnel.", defaultsBefore, defaultRoutes())
    }

    @Test(timeout = 160_000)
    fun xrayWintunUsesAuthenticatedOutboundAndRestoresRoutes() = withNetwork { root, client ->
        val target = "198.51.100.23"
        val before = defaultRoutes()
        var adapter: String? = null
        SocksEcho(target).use { proxy ->
            val profile = JSONObject().put("protocol", "socks").put("settings", JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1")
                    .put("port", proxy.port).put("users", JSONArray().put(JSONObject()
                        .put("user", proxy.user).put("pass", proxy.password))))))
            AutoCloseable(client::disconnect).use {
                client.connect("Windows Xray integration", profile.toString(), autoReconnect = false)
                adapter = tunnelAdapter(root)
                assertTrue(client.status().connected)
                await("authenticated Xray Wintun echo") { matchesEcho(target, SocksEcho.targetPort) }
                assertTrue("The proxy must authenticate the TUN connection.", proxy.authenticated.get() > 0)
            }
        }
        assertFalse(client.status().connected)
        val stopped = checkNotNull(adapter)
        assertEquals("inactive", powerShell(
            "if (!(Get-NetAdapter -Name '$stopped' -IncludeHidden -ErrorAction SilentlyContinue | Where-Object Status -eq 'Up')) { 'inactive' }"
        ))
        assertEquals(before, defaultRoutes())
    }

    private fun withNetwork(block: (Path, WindowsTunnels) -> Unit) {
        val root = Files.createTempDirectory("whitegame-windows-integration-")
        try {
            WindowsTunnels(root).use { block(root, it) }
        } finally {
            // Keep any unfinished session in place for the worker to recover.
            runCatching {
                Files.deleteIfExists(root.resolve("windows-network"))
                Files.deleteIfExists(root)
            }
        }
    }

    private fun tunnelAdapter(root: Path): String = JSONObject(
        Files.readString(root.resolve("windows-network/tunnel/session.json"))
    ).getString("adapter").also { require(it.matches(Regex("WG[0-9a-f]{10}"))) }

    private fun matchesEcho(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2500)
            socket.soTimeout = 2500
            val sent = ByteArray(48).also { SecureRandom().nextBytes(it) }
            socket.outputStream.write(sent)
            socket.getInputStream().readNBytes(sent.size).contentEquals(sent)
        }
    }.getOrDefault(false)

    private fun await(description: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22)
        do {
            if (check()) return
            Thread.sleep(350)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out waiting for $description.")
    }

    private fun withIpv4RouteGuards(block: () -> Unit) {
        // The complement of the benchmark network 198.18.0.0/15, with the
        // other /1 divided into /2s. Runner traffic keeps its normal gateway
        // while the fixture address can only reach the installed tunnel /1.
        val protectedPrefixes = listOf(
            "0.0.0.0/2", "64.0.0.0/2", "128.0.0.0/2", "224.0.0.0/3", "208.0.0.0/4", "200.0.0.0/5",
            "192.0.0.0/6", "196.0.0.0/7", "199.0.0.0/8", "198.128.0.0/9", "198.64.0.0/10", "198.32.0.0/11",
            "198.0.0.0/12", "198.24.0.0/13", "198.20.0.0/14", "198.16.0.0/15"
        )
        val baseline = activeRoutes(protectedPrefixes.toSet())
        val prefixes = protectedPrefixes.joinToString(",") { "'$it'" }
        val guard = JSONObject(powerShell("""
            ${'$'}default = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -PolicyStore ActiveStore |
                Where-Object { ${'$'}_.NextHop -ne '0.0.0.0' } |
                Sort-Object { ${'$'}_.RouteMetric + ${'$'}_.InterfaceMetric } | Select-Object -First 1
            if (!${'$'}default) { throw 'The Windows VM has no IPv4 default gateway.' }
            ${'$'}existing = @(Get-NetRoute -PolicyStore ActiveStore -AddressFamily IPv4 | Select-Object -ExpandProperty DestinationPrefix)
            ${'$'}created = @()
            ${'$'}lifetime = New-TimeSpan -Minutes 5
            try {
                foreach (${'$'}prefix in @($prefixes)) {
                    if (${'$'}existing -contains ${'$'}prefix) { continue }
                    ${'$'}created += New-NetRoute -DestinationPrefix ${'$'}prefix -InterfaceIndex ${'$'}default.InterfaceIndex `
                        -NextHop ${'$'}default.NextHop -RouteMetric ${'$'}default.RouteMetric -PolicyStore ActiveStore `
                        -ValidLifetime ${'$'}lifetime -PreferredLifetime ${'$'}lifetime
                }
                @{ index = ${'$'}default.InterfaceIndex; gateway = ${'$'}default.NextHop; prefixes = @(${'$'}created.DestinationPrefix) } |
                    ConvertTo-Json -Compress
            } catch {
                if (${'$'}created.Count) { Remove-NetRoute -InputObject ${'$'}created -Confirm:${'$'}false }
                throw
            }
        """.trimIndent(), timeoutSeconds = 45))
        val index = guard.getInt("index")
        val gateway = guard.getString("gateway").also { require(it.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))) }
        val created = guard.getJSONArray("prefixes")
        val owned = (0 until created.length()).mapNotNull { created.optString(it).takeIf(protectedPrefixes::contains) }
        try {
            block()
        } finally {
            if (owned.isNotEmpty()) {
                val ownedPrefixes = owned.joinToString(",") { "'$it'" }
                powerShell("""
                    ${'$'}routes = @(Get-NetRoute -InterfaceIndex $index -NextHop '$gateway' -PolicyStore ActiveStore |
                        Where-Object { ${'$'}_.DestinationPrefix -in @($ownedPrefixes) })
                    if (${'$'}routes.Count) { Remove-NetRoute -InputObject ${'$'}routes -Confirm:${'$'}false }
                """.trimIndent(), timeoutSeconds = 45)
            }
        }
        assertEquals("The original routes must survive the IPv4 guard.", baseline, activeRoutes(protectedPrefixes.toSet()))
    }

    private data class DnsSetting(val guid: String, val servers: List<String>, val staticValue: String)

    private fun dnsSettings(): List<DnsSetting> {
        val json = powerShell("""
            ${'$'}rows = @(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Select-Object -ExpandProperty InterfaceIndex -Unique |
                ForEach-Object {
                    ${'$'}adapter = Get-NetAdapter -InterfaceIndex ${'$'}_ -IncludeHidden -ErrorAction SilentlyContinue
                    if (${'$'}adapter -and ${'$'}adapter.Status -eq 'Up') {
                        ${'$'}guid = [guid]${'$'}adapter.InterfaceGuid
                        ${'$'}key = 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + ${'$'}guid.ToString('B')
                        ${'$'}static = Get-ItemPropertyValue -LiteralPath ${'$'}key -Name NameServer -ErrorAction SilentlyContinue
                        ${'$'}server = Get-DnsClientServerAddress -InterfaceIndex ${'$'}_ -AddressFamily IPv4
                        @{ guid = ${'$'}guid.ToString(); servers = @(${'$'}server.ServerAddresses); static = [string]${'$'}static }
                    }
                })
            ConvertTo-Json -InputObject ${'$'}rows -Depth 5 -Compress
        """.trimIndent())
        val rows = JSONArray(json)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val servers = row.getJSONArray("servers")
            DnsSetting(row.getString("guid"), (0 until servers.length()).map(servers::getString), row.getString("static"))
        }.sortedBy(DnsSetting::guid)
    }

    private fun defaultRoutes() = activeRoutes(setOf("0.0.0.0/0", "::/0"))

    private fun activeRoutes(prefixes: Set<String>): List<String> {
        val selected = prefixes.joinToString(",") { "'$it'" }
        return powerShell("""
        Get-NetRoute -PolicyStore ActiveStore |
            Where-Object { ${'$'}_.DestinationPrefix -in @($selected) } |
            ForEach-Object { @(${'$'}_.InterfaceIndex, ${'$'}_.DestinationPrefix, ${'$'}_.NextHop, ${'$'}_.RouteMetric) -join '|' }
        """.trimIndent()).lineSequence().filter(String::isNotBlank).sorted().toList()
    }

    private fun powerShell(command: String, timeoutSeconds: Long = 15): String {
        val script = "\$ErrorActionPreference = 'Stop'\n$command\nexit 0"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).start()
        try {
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            val error = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Windows inspection command timed out." }
            check(process.exitValue() == 0) { "Windows inspection command failed: ${error.get(2, TimeUnit.SECONDS).trim()}" }
            return output.get(2, TimeUnit.SECONDS).trim()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Accept only the authenticated test destination and echo its bytes. */
    private class SocksEcho(private val target: String) : AutoCloseable {
        val user = "whitegame-ci"
        val password = UUID.randomUUID().toString()
        val authenticated = AtomicInteger()
        private val listener = ServerSocket(0, 15, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { work -> Thread(work, "local-socks-fixture").apply { isDaemon = true } }
        val port get() = listener.localPort

        init {
            workers.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    workers.execute { socket.use { runCatching { serve(it) } } }
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 4000
            val from = DataInputStream(socket.getInputStream())
            val to = socket.getOutputStream()
            check(from.readUnsignedByte() == 5)
            val methods = ByteArray(from.readUnsignedByte()).apply { from.readFully(this) }
            check(2.toByte() in methods)
            to.write(byteArrayOf(5, 2))
            check(from.readUnsignedByte() == 1)
            check(from.string(from.readUnsignedByte()) == user)
            check(from.string(from.readUnsignedByte()) == password)
            to.write(byteArrayOf(1, 0))
            check(from.readUnsignedByte() == 5 && from.readUnsignedByte() == 1 && from.readUnsignedByte() == 0)
            val host = when (from.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).apply { from.readFully(this) }).hostAddress
                3 -> from.string(from.readUnsignedByte())
                4 -> InetAddress.getByAddress(ByteArray(16).apply { from.readFully(this) }).hostAddress
                else -> return
            }
            val accepted = host == target && from.readUnsignedShort() == targetPort
            to.write(byteArrayOf(5, if (accepted) 0 else 2, 0, 1, 127, 0, 0, 1, 0, 0))
            if (!accepted) return
            authenticated.incrementAndGet()
            from.copyTo(to)
        }

        override fun close() {
            listener.close()
            workers.shutdownNow()
        }

        private fun DataInputStream.string(length: Int): String = ByteArray(length).apply { readFully(this) }.toString(Charsets.UTF_8)

        companion object { const val targetPort = 4433 }
    }
}

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
        // The remote address belongs only to the userspace peer. A direct
        // Windows connection to it could not satisfy the random echo check.
        val remote = "10.203.218.2"
        val local = "10.203.218.1"
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
                AllowedIPs = $remote/32
                Endpoint = 127.0.0.1:${ready[1].toInt()}
            """.trimIndent()
            client.connect("Windows WireGuard integration", config, autoReconnect = false)
            adapter = tunnelAdapter(root)
            assertTrue(client.status().connected)
            await("authenticated WireGuard echo") { matchesEcho(remote, ready[2].toInt()) }
            await("WireGuard handshake and bidirectional counters") {
                val status = client.status()
                status.lastHandshakeSeconds != null && (status.rxBytes ?: 0L) > 0 && (status.txBytes ?: 0L) > 0
            }
            val joined = adapter
            val service = "'WireGuardTunnel$" + joined + "'"
            assertEquals("yes", powerShell("if (Get-Service -Name $service -ErrorAction SilentlyContinue) { 'yes' }"))
        } finally {
            try {
                client.disconnect()
            } finally {
                fixture.outputStream.close()
                if (!fixture.waitFor(5, TimeUnit.SECONDS)) fixture.destroyForcibly()
            }
        }
        assertFalse(client.status().connected)
        val service = "'WireGuardTunnel$" + checkNotNull(adapter) + "'"
        assertEquals("gone", powerShell("if (!(Get-Service -Name $service -ErrorAction SilentlyContinue)) { 'gone' }"))
        assertNoTestRoute(remote)
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
            try {
                client.connect("Windows Xray integration", profile.toString(), autoReconnect = false)
                adapter = tunnelAdapter(root)
                assertTrue(client.status().connected)
                await("authenticated Xray Wintun echo") { matchesEcho(target, SocksEcho.targetPort) }
                assertTrue("The proxy must authenticate the TUN connection.", proxy.authenticated.get() > 0)
            } finally {
                client.disconnect()
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

    private fun assertNoTestRoute(host: String) {
        val route = powerShell("Get-NetRoute -DestinationPrefix '$host/32' -ErrorAction SilentlyContinue | Out-String")
        assertTrue("The native service must remove its test route.", route.isBlank())
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

    private fun defaultRoutes(): List<String> = powerShell("""
        Get-NetRoute -DestinationPrefix @('0.0.0.0/0', '::/0') -ErrorAction SilentlyContinue |
            ForEach-Object { @(${'$'}_.InterfaceIndex, ${'$'}_.DestinationPrefix, ${'$'}_.NextHop, ${'$'}_.RouteMetric) -join '|' }
    """.trimIndent()).lineSequence().filter(String::isNotBlank).sorted().toList()

    private fun powerShell(command: String): String {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Windows inspection command failed." }
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

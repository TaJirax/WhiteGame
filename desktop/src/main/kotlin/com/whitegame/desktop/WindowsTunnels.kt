package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.WireGuardProbe
import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

object WindowsEngines {
    fun xray(): Path = executable("WHITEGAME_XRAY", "xray.exe", "Xray", emptyList())

    fun wireGuard(): Path = executable("WHITEGAME_WIREGUARD", "wireguard.exe", "WireGuard for Windows", listOf("WireGuard"))

    fun amnezia(): Path = executable(
        "WHITEGAME_AMNEZIAWG", "amneziawg.exe", "AmneziaWG for Windows", listOf("AmneziaWG", "AmneziaVPN")
    )

    private fun executable(variable: String, file: String, label: String, installedFolders: List<String>): Path {
        System.getenv(variable)?.takeIf(String::isNotBlank)?.let { override ->
            val location = Path.of(override).toAbsolutePath().normalize()
            check(Files.isRegularFile(location)) { "$variable does not point to a $label executable." }
            return location
        }
        val codeHome = runCatching {
            Path.of(WindowsEngines::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val appRoots = listOfNotNull(codeHome?.parent, codeHome?.parent?.parent)
        val bundled = appRoots.flatMap { listOf(it.resolve("engines/xray/$file"), it.resolve("engines/$file")) }
        val installed = listOfNotNull(System.getenv("ProgramW6432"), System.getenv("ProgramFiles"))
            .flatMap { root -> installedFolders.map { Path.of(root, it, file) } }
        val onPath = System.getenv("PATH").orEmpty().split(';').filter(String::isNotBlank)
            .mapNotNull { runCatching { Path.of(it.trim('"'), file) }.getOrNull() }
            .filter(Path::isAbsolute)
        return (installed + bundled + onPath).firstOrNull(Files::isRegularFile)?.toAbsolutePath()?.normalize()
            ?: error("$label was not found. Install it or set $variable to the executable path.")
    }
}

data class WindowsTunnelStatus(
    val connected: Boolean,
    val name: String?,
    val detail: String,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val lastHandshakeSeconds: Long? = null,
    val phase: String? = null
)

/** Privilege is confined to each explicitly started network session. */
class WindowsTunnels(directory: Path) : AutoCloseable {
    private val root = directory.toAbsolutePath().normalize().resolve("windows-network")
    private val tunnel = root.resolve("tunnel")
    private val dns = root.resolve("dns")
    private val supervisor by lazy {
        requireNotNull(javaClass.getResourceAsStream("/whitegame/WindowsNetwork.ps1"))
            .bufferedReader().use { it.readText() }
    }

    @Synchronized
    fun connect(
        name: String,
        text: String,
        killSwitch: Boolean = false,
        autoReconnect: Boolean = true,
        preferredDns: List<String> = emptyList()
    ) {
        val adapter = "WG" + UUID.randomUUID().toString().replace("-", "").take(10)
        val dnsServers = normalizeDns(preferredDns.ifEmpty { currentDns() })
        val backend = prepare(text, adapter, dnsServers, killSwitch)
        disconnect()
        val manifest = JSONObject()
            .put("kind", backend.kind).put("name", name).put("adapter", adapter)
            .put("reconnect", autoReconnect)
        start(tunnel, manifest, backend.executable, backend.payload)
    }

    @Synchronized
    fun disconnect() = stop(tunnel)

    fun status(): WindowsTunnelStatus {
        val manifest = read(tunnel.resolve("session.json"))
            ?: return WindowsTunnelStatus(false, null, "Disconnected", phase = "stopped")
        val state = read(tunnel.resolve("status.json"))
        val status = state?.optString("phase")
        if (status in setOf("starting", "running", "stopping") && !fresh(tunnel)) {
            return WindowsTunnelStatus(
                false, manifest.optString("name"), "The Windows tunnel supervisor stopped. Disconnect to clean up.",
                phase = "recovery"
            )
        }
        return WindowsTunnelStatus(
            status == "running",
            manifest.optString("name"),
            state?.optString("detail")?.takeIf(String::isNotEmpty) ?: "Waiting for Windows to start the tunnel…",
            state?.optLong("rx", -1)?.takeIf { it >= 0 },
            state?.optLong("tx", -1)?.takeIf { it >= 0 },
            state?.optLong("handshake", -1)?.takeIf { it >= 0 },
            phase = status
        )
    }

    @Synchronized
    fun applyDns(addresses: List<String>) {
        val servers = normalizeDns(addresses)
        if (servers.isEmpty()) {
            restoreDns()
            return
        }
        check(!status().connected) { "Disconnect the active tunnel before changing Windows DNS." }
        if (currentDns() == servers) return
        restoreDns()
        start(dns, JSONObject().put("kind", "dns").put("servers", JSONArray(servers)), null, null)
    }

    @Synchronized
    fun restoreDns() = stop(dns)

    fun currentDns(): List<String> {
        val state = read(dns.resolve("status.json"))
        val addresses = read(dns.resolve("session.json"))?.optJSONArray("servers")
        if (state?.optString("phase") != "running" || !fresh(dns) || addresses == null) return emptyList()
        return (0 until addresses.length()).map(addresses::getString)
    }

    @Synchronized
    override fun close() {
        // Both running helpers can restore their own settings without another administrator prompt.
        val sessions = listOf(tunnel, dns).filter(Files::isDirectory)
        for (session in sessions) Files.writeString(session.resolve("stop"), "")
        val failed = sessions.filterNot { session ->
            if (fresh(session)) await(session, Duration.ofSeconds(35)) { it.optString("phase") in terminal }
            cleanupIfFinished(session)
        }
        if (failed.isNotEmpty()) {
            error("Windows still needs to restore network settings. Use Disconnect or Restore DNS before closing.")
        }
    }

    private data class Backend(val kind: String, val executable: Path, val payload: String)

    private fun prepare(text: String, adapter: String, dnsServers: List<String>, killSwitch: Boolean): Backend {
        if (XrayConfig.looksLikeXray(text)) {
            val profile = XrayConfig.parse(text)
            if (profile.protocol.lowercase() in setOf("freedom", "blackhole", "dns", "loopback")) {
                throw ConfigException("This config has no tunnel outbound.")
            }
            val routes = listOf("0.0.0.0/0", "::/0")
            return xrayBackend(profile, adapter, dnsServers, routes, killSwitch, wireGuard = false)
        }

        val config = AwgConfig.parse(text)
        if (config.iface.includedApplications.isNotEmpty() || config.iface.excludedApplications.isNotEmpty()) {
            throw ConfigException("This profile contains Android app exclusions or inclusions. Remove those fields to use it on Windows.")
        }
        val overridden = config.copy(iface = config.iface.copy(
            dnsServers = dnsServers.ifEmpty { config.iface.dnsServers },
            dnsSearchDomains = if (dnsServers.isEmpty()) config.iface.dnsSearchDomains else emptyList()
        ))
        if (config.peers.any { it.reserved.isNotEmpty() }) {
            val profile = WireGuardProbe.forSpeedTest(overridden)
            return xrayBackend(profile, adapter, overridden.iface.dnsServers, overridden.allowedIps, killSwitch, wireGuard = true)
        }
        val updated = windowsWireGuardConfig(overridden, killSwitch)
        val amnezia = config.iface.amnezia.isNotEmpty()
        val executable = if (amnezia) WindowsEngines.amnezia() else WindowsEngines.wireGuard()
        return Backend(if (amnezia) "amnezia" else "wireguard", executable, updated.toConfText())
    }

    private fun xrayBackend(
        profile: XrayConfig.Parsed, adapter: String, dns: List<String>, routes: List<String>,
        killSwitch: Boolean, wireGuard: Boolean
    ): Backend {
        val executable = WindowsEngines.xray()
        check(Files.isRegularFile(executable.resolveSibling("wintun.dll"))) {
            "Xray device-wide routing requires the Windows Wintun DLL next to xray.exe."
        }
        return Backend("xray", executable, windowsXrayConfig(profile, adapter, dns, routes, killSwitch, wireGuard))
    }

    private fun start(session: Path, manifest: JSONObject, executable: Path?, payload: String?) {
        Files.createDirectories(root)
        Files.createDirectory(session)
        var launched = false
        try {
            manifest.put("lease", UUID.randomUUID().toString())
            if (payload != null) manifest.put("payloadSha", sha256(payload.toByteArray(Charsets.UTF_8)))
            Files.writeString(session.resolve("session.json"), manifest.toString())
            if (payload != null) Files.writeString(configPath(session, manifest), payload)
            val worker = launch(session, manifest, executable, recover = false)
            launched = true
            val state = await(session, Duration.ofSeconds(45)) {
                it.optString("worker") == worker && it.optString("phase") !in setOf("starting", "stopping")
            }
            check(state?.optString("phase") == "running") {
                state?.optString("detail")?.takeIf(String::isNotBlank)
                    ?: "Windows did not finish setting up the network session."
            }
        } catch (e: Exception) {
            if (launched) Files.writeString(session.resolve("stop"), "")
            else cleanupIfFinished(session, allowMissing = true)
            throw e
        }
    }

    private fun stop(session: Path) {
        if (!Files.isDirectory(session)) return
        if (cleanupIfFinished(session)) return
        val manifest = read(session.resolve("session.json"))
            ?: error("The Windows network session is being started by another WhiteGame window.")
        Files.writeString(session.resolve("stop"), "")
        // A fresh worker has the original adapter/service handles and can restore without UAC.
        val previousPhase = read(session.resolve("status.json"))?.optString("phase")
        if (previousPhase != "recovery" && fresh(session)) {
            val state = await(session, Duration.ofSeconds(35)) { it.optString("phase") in terminal }
            if (cleanupIfFinished(session)) return
            if (state == null && fresh(session)) error("Windows is still closing the network session. Try again in a moment.")
        }
        val executable = when (manifest.getString("kind")) {
            "wireguard" -> WindowsEngines.wireGuard()
            "amnezia" -> WindowsEngines.amnezia()
            "xray" -> WindowsEngines.xray()
            else -> null
        }
        val worker = launch(session, manifest, executable, recover = true)
        val state = await(session, Duration.ofSeconds(35)) {
            it.optString("worker") == worker && it.optString("phase") in terminal
        }
        check(cleanupIfFinished(session)) {
            state?.optString("detail")?.takeIf(String::isNotBlank) ?: "Windows could not restore the prior network settings."
        }
    }

    private fun launch(session: Path, manifest: JSONObject, executable: Path?, recover: Boolean): String {
        val shell = Path.of(System.getenv("SystemRoot") ?: "C:/Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val workerId = UUID.randomUUID().toString()
        val child = buildString {
            appendLine("\$session = " + psLiteral(session.toString()))
            appendLine("\$engine = " + psLiteral(executable?.toString().orEmpty()))
            appendLine("\$ownerProcessId = " + ProcessHandle.current().pid())
            appendLine("\$workerId = " + psLiteral(workerId))
            appendLine("\$recover = " + if (recover) "\$true" else "\$false")
            appendLine("\$manifest = ConvertFrom-Json -InputObject " + psLiteral(manifest.toString()))
            appendLine(supervisor)
        }
        val worker = session.resolve("network-worker.ps1")
        Files.writeString(worker, child)
        // Verify the exact bytes before the elevated process evaluates a script handed over by the UI.
        val bootstrap = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}contents = [IO.File]::ReadAllBytes(${psLiteral(worker.toString())})
            ${'$'}hash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash(${'$'}contents)).Replace('-', '')
            if (${'$'}hash -ne '${sha256(child.toByteArray(Charsets.UTF_8))}') { exit 1 }
            & ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString(${'$'}contents)))
        """.trimIndent()
        val outer = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent()
                ${'$'}principal = [Security.Principal.WindowsPrincipal]::new(${'$'}identity)
                ${'$'}launch = @{
                    FilePath = ${psLiteral(shell.toString())}
                    WindowStyle = 'Hidden'
                    ArgumentList = '-NoProfile -NonInteractive -EncodedCommand ${encoded(bootstrap)}'
                }
                if (!${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
                    ${'$'}launch.Verb = 'RunAs'
                }
                Start-Process @launch | Out-Null
                exit 0
            } catch { exit 1 }
        """.trimIndent()
        val process = ProcessBuilder(shell.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(outer))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            Files.writeString(session.resolve("stop"), "")
            process.destroyForcibly()
            error("Windows did not respond to the administrator request.")
        }
        check(process.exitValue() == 0) { "Windows administrator permission was declined or unavailable." }
        return workerId
    }

    private fun configPath(session: Path, manifest: JSONObject): Path {
        val adapter = manifest.optString("adapter")
        check(adapter.matches(Regex("WG[0-9a-f]{10}"))) { "Invalid Windows tunnel session." }
        val suffix = if (manifest.getString("kind") == "xray") ".json" else ".conf"
        return session.resolve(adapter + suffix)
    }

    private fun cleanupIfFinished(session: Path, allowMissing: Boolean = false): Boolean {
        val state = read(session.resolve("status.json"))
        val phase = state?.optString("phase")
        if (phase !in setOf("stopped", "failed") && !(allowMissing && phase == null)) return false
        // The supervisor signals a terminal phase only after it is finished using these files.
        Files.newDirectoryStream(session).use { files -> files.forEach(Files::deleteIfExists) }
        Files.deleteIfExists(session)
        return true
    }

    private fun await(session: Path, timeout: Duration, accept: (JSONObject) -> Boolean): JSONObject? {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            val state = read(session.resolve("status.json"))
            if (state != null && accept(state)) return state
            Thread.sleep(180)
        } while (System.nanoTime() < deadline)
        return null
    }

    private fun fresh(session: Path): Boolean = runCatching {
        val modified = Files.getLastModifiedTime(session.resolve("status.json")).toInstant()
        Duration.between(modified, Instant.now()) < Duration.ofSeconds(14)
    }.getOrDefault(false)

    private fun read(path: Path): JSONObject? =
        runCatching { JSONObject(Files.readString(path).removePrefix("\uFEFF")) }.getOrNull()

    private companion object {
        val terminal = setOf("stopped", "failed", "recovery")

        fun psLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

        fun encoded(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_16LE))

        fun sha256(value: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

        fun normalizeDns(addresses: List<String>): List<String> = addresses.distinct().map { address ->
            val parsed = when {
                address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) &&
                    address.split('.').all { it.toInt() <= 255 } -> InetAddress.getByName(address)
                ':' in address && address.matches(Regex("[0-9a-fA-F:.]+")) ->
                    runCatching { InetAddress.getByName(address) }.getOrNull() as? Inet6Address
                else -> null
            }
            parsed?.hostAddress ?: throw ConfigException("Enter numeric IPv4 or IPv6 DNS server addresses.")
        }
    }
}

internal fun windowsWireGuardConfig(config: AwgConfig, killSwitch: Boolean): AwgConfig {
    if (killSwitch) {
        if (config.peers.size != 1 || !config.isFullTunnel)
            throw ConfigException("Windows WireGuard leak blocking needs one peer with a default route.")
        return config
    }
    // The Windows client's automatic VPN firewall is keyed to a literal /0 route.
    val peers = config.peers.map { peer ->
        peer.copy(allowedIps = peer.allowedIps.flatMap { route ->
            when (route) {
                "0.0.0.0/0" -> listOf("0.0.0.0/1", "128.0.0.0/1")
                "::/0" -> listOf("::/1", "8000::/1")
                else -> listOf(route)
            }
        }.distinct())
    }
    return config.copy(peers = peers)
}

internal fun windowsXrayConfig(
    profile: XrayConfig.Parsed, adapter: String, dns: List<String>, routes: List<String>,
    killSwitch: Boolean, wireGuard: Boolean
): String {
    val servers = dns.ifEmpty { listOf("1.1.1.1") }
    val root = JSONObject(XrayConfig.tunConfig(profile, servers))
    val tun = root.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
    tun.put("name", adapter)
        .put("gateway", JSONArray(listOf("198.18.0.1/30", "fd01:7767::1/64")))
        .put("dns", JSONArray(servers))
        .put("autoSystemRoutingTable", JSONArray(routes))
        .put("autoOutboundsInterface", "auto")
        .put("autoSystemWfpBlockLeak", JSONArray(if (killSwitch) listOf("all") else listOf("dns", "misconfigtun")))
    if (wireGuard) root.remove("routing")
    root.put("log", JSONObject().put("loglevel", "warning"))
    return root.toString()
}

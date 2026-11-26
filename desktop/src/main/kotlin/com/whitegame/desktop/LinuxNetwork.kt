package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.xray.XrayConfig
import org.json.JSONArray
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Linux networking leases end when the UI closes its pipe or exits. */
internal class LinuxNetwork : DesktopNetwork {
    private data class Tunnel(val adapter: String, val name: String, val worker: Process)

    private var tunnel: Tunnel? = null
    private var dns: Process? = null
    private val workerSource by lazy {
        checkNotNull(javaClass.getResourceAsStream("/whitegame/LinuxNetwork.sh"))
            .bufferedReader().use { it.readText() }
    }

    @Synchronized
    override fun connect(name: String, text: String, killSwitch: Boolean, autoReconnect: Boolean, preferredDns: List<String>) {
        require(!XrayConfig.looksLikeXray(text)) { "On Linux, Xray profiles can be tested in Network → Configs. Device-wide Xray is available on Windows." }
        val profile = AwgConfig.parse(text)
        require(profile.peers.none { it.reserved.isNotEmpty() }) { "WARP Reserved profiles can be tested in Network → Configs on Linux." }
        require(profile.iface.includedApplications.isEmpty() && profile.iface.excludedApplications.isEmpty()) {
            "Remove Android app inclusion or exclusion rules before connecting this profile on Linux."
        }
        require(!killSwitch) { "The separate WhiteGame kill switch is available on Windows." }
        val engine = if (profile.iface.amnezia.isEmpty()) DesktopEngines.wireGuard() else DesktopEngines.amnezia()
        val config = if (preferredDns.isEmpty()) profile else profile.copy(iface = profile.iface.copy(
            dnsServers = preferredDns.map(::numericAddress), dnsSearchDomains = emptyList()
        ))
        val adapter = "wg" + UUID.randomUUID().toString().replace("-", "").take(10)
        disconnect()
        val payload = Base64.getEncoder().encodeToString(config.toConfText().toByteArray(Charsets.UTF_8))
        val worker = startWorker(listOf("tunnel", engine.toString(), adapter), payload,
            "Linux could not start the tunnel. Check administrator approval, wg-quick, and your profile.")
        tunnel = Tunnel(adapter, name, worker)
    }

    @Synchronized
    override fun disconnect() {
        val active = tunnel ?: return
        endWorker(active.worker, "Linux could not finish shutting down the tunnel.")
        tunnel = null
    }

    @Synchronized
    override fun status(): WindowsTunnelStatus {
        val active = tunnel ?: return WindowsTunnelStatus(false, null, "Disconnected", phase = "stopped")
        val adapter = Path.of("/sys/class/net", active.adapter)
        val up = active.worker.isAlive && Files.isDirectory(adapter)
        return WindowsTunnelStatus(
            up, active.name, if (up) "Tunnel running" else "The Linux tunnel stopped.",
            rxBytes = counter(adapter, "rx_bytes"),
            txBytes = counter(adapter, "tx_bytes"),
            phase = if (up) "running" else "failed"
        )
    }

    @Synchronized
    override fun applyDns(addresses: List<String>) {
        val servers = addresses.map(::numericAddress).distinct()
        if (servers.isEmpty()) {
            restoreDns()
            return
        }
        check(!status().connected) { "Disconnect the tunnel before applying system DNS." }
        val resolver = DesktopEngines.linuxExecutable("resolvectl")
        val adapter = activeDefaultLink()
        restoreDns()
        dns = startWorker(listOf("dns", resolver.toString(), adapter) + servers, null,
            "Linux could not set DNS. Check that systemd-resolved is active and administrator approval was granted.")
    }

    @Synchronized
    override fun restoreDns() {
        val active = dns ?: return
        endWorker(active, "Linux could not restore the previous DNS settings.")
        dns = null
    }

    override fun close() {
        try {
            disconnect()
        } finally {
            restoreDns()
        }
    }

    private fun activeDefaultLink(): String {
        val ip = DesktopEngines.linuxExecutable("ip").toString()
        for (family in listOf("-4", "-6")) {
            val output = commandOutput(listOf(ip, "-j", family, "route", "show", "default"))
            val routes = JSONArray(output)
            val adapter = (0 until routes.length()).mapNotNull(routes::optJSONObject)
                .filter { it.optString("dev").matches(LINK) }
                .minByOrNull { it.optInt("metric") }?.optString("dev")
            if (adapter != null) return adapter
        }
        error("No active default-route network adapter was found.")
    }

    private fun startWorker(args: List<String>, payload: String?, failure: String): Process {
        val privilege = if (commandOutput(listOf(DesktopEngines.linuxExecutable("id").toString(), "-u")).trim() == "0") {
            emptyList()
        } else {
            listOf(DesktopEngines.linuxExecutable("pkexec").toString(), "--disable-internal-agent")
        }
        val process = ProcessBuilder(privilege + listOf("/bin/bash", "-c", workerSource, "whitegame") + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            if (payload != null) {
                process.outputStream.write((payload + "\n").toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readLine() } }
            check(ready.get(90, TimeUnit.SECONDS) == "READY") { failure }
            return process
        } catch (e: Exception) {
            process.outputStream.close()
            if (process.isAlive) process.destroy()
            throw IOException(failure, e)
        }
    }

    private fun endWorker(process: Process, failure: String) {
        process.outputStream.close()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { failure }
    }

    private fun commandOutput(args: List<String>): String {
        val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val result = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not inspect the Linux network." }
            return result.get(1, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun counter(adapter: Path, field: String): Long? =
        runCatching { Files.readString(adapter.resolve("statistics").resolve(field)).trim().toLong() }.getOrNull()

    private fun numericAddress(ip: String): String {
        val ipv4 = ip.split('.').let { parts ->
            parts.size == 4 && parts.all { it.matches(OCTET) && it.toInt() <= 255 }
        }
        require(ipv4 || (':' in ip && ip.matches(IPV6))) { "Enter a numeric DNS address." }
        return InetAddress.getByName(ip).hostAddress
    }

    private companion object {
        val LINK = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,14}")
        val OCTET = Regex("[0-9]{1,3}")
        val IPV6 = Regex("[0-9a-fA-F:.]+")
    }
}

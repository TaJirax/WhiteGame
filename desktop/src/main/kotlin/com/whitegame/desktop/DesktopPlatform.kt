package com.whitegame.desktop

import java.nio.file.Files
import java.nio.file.Path

internal object DesktopPlatform {
    val linux = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
    val name = if (linux) "Linux" else "Windows"

    fun dataDirectory(): Path {
        val home = Path.of(System.getProperty("user.home"))
        if (linux) {
            val data = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?.takeIf(Path::isAbsolute) ?: home.resolve(".local/share")
            return data.resolve("whitegame")
        }
        return (System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of) ?: home)
            .resolve("WhiteGame")
    }

    fun network(directory: Path): DesktopNetwork =
        if (linux) LinuxNetwork() else WindowsNetworkAdapter(WindowsTunnels(directory))
}

internal interface DesktopNetwork : AutoCloseable {
    fun connect(name: String, text: String, killSwitch: Boolean, autoReconnect: Boolean, preferredDns: List<String>)
    fun disconnect()
    fun status(): WindowsTunnelStatus
    fun applyDns(addresses: List<String>)
    fun restoreDns()
}

private class WindowsNetworkAdapter(private val backend: WindowsTunnels) : DesktopNetwork {
    override fun connect(name: String, text: String, killSwitch: Boolean, autoReconnect: Boolean, preferredDns: List<String>) =
        backend.connect(name, text, killSwitch, autoReconnect, preferredDns)
    override fun disconnect() = backend.disconnect()
    override fun status() = backend.status()
    override fun applyDns(addresses: List<String>) = backend.applyDns(addresses)
    override fun restoreDns() = backend.restoreDns()
    override fun close() = backend.close()
}

internal object DesktopEngines {
    fun xray(): Path {
        if (!DesktopPlatform.linux) return WindowsEngines.xray()
        System.getenv("WHITEGAME_XRAY")?.takeIf(String::isNotBlank)?.let {
            val path = Path.of(it).toAbsolutePath().normalize()
            check(Files.isRegularFile(path) && Files.isExecutable(path)) { "WHITEGAME_XRAY must point to an executable Xray binary." }
            return path
        }
        val codeHome = runCatching {
            Path.of(javaClass.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val roots = generateSequence(codeHome?.parent) { it.parent }.take(3)
        return roots.map { it.resolve("engines/xray/xray") }.firstOrNull(Files::isExecutable)
            ?: linuxExecutable("xray")
    }

    fun wireGuard(): Path = if (DesktopPlatform.linux) linuxExecutable("wg-quick") else WindowsEngines.wireGuard()

    fun amnezia(): Path = if (DesktopPlatform.linux) linuxExecutable("awg-quick") else WindowsEngines.amnezia()

    fun linuxExecutable(name: String): Path = listOf("/usr/bin", "/usr/sbin", "/usr/local/bin", "/usr/local/sbin")
        .map { Path.of(it, name) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: error("Install $name to use this feature on Linux.")
}

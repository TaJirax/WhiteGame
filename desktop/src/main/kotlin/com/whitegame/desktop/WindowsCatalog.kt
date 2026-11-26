package com.whitegame.desktop

import com.whitegame.app.model.DnsItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class WindowsGame(val id: String, val title: String, val category: String, val hosts: List<String>)

internal object WindowsCatalog {
    private const val CATALOG_LIMIT = 2 * 1024 * 1024
    private val steamLibraries = Regex("""(?m)^\s*"path"\s+"([^"]+)"""")
    private val steamTitle = Regex("""(?m)^\s*"name"\s+"([^"]+)"""")

    fun games(directory: Path): List<WindowsGame> {
        val override = directory.resolve("games.json")
        return readGames(if (Files.exists(override)) Files.readString(override) else resource("games_online.json"))
    }

    fun refresh(directory: Path, url: String): List<WindowsGame> {
        val uri = URI(url.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null) { "Use an HTTPS game catalog URL." }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
        }
        val text = try {
            require(connection.responseCode in 200..299) { "Catalog request returned HTTP ${connection.responseCode}." }
            val bytes = connection.inputStream.use { it.readNBytes(CATALOG_LIMIT + 1) }
            require(bytes.size <= CATALOG_LIMIT) { "Catalog exceeds 2 MB." }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
        val games = readGames(text)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("games.json"), text)
        return games
    }

    fun resolvers(): List<DnsItem> {
        val data = JSONArray(resource("dns.json"))
        return (0 until data.length()).map { i ->
            val row = data.getJSONObject(i)
            DnsItem(row.optInt("id", i), row.getString("ip"), row.getString("provider"),
                row.optString("notes"), row.optString("regionHint"), row.optBoolean("gaming", true))
        }
    }

    fun installedGames(): Set<String> {
        val inventory = if (DesktopPlatform.linux) JSONObject() else installedInventory()
        val names = inventory.objects("apps").map { it.optString("DisplayName") }.toMutableList()
        val steam = buildList {
            inventory.optString("steam").takeIf(String::isNotBlank)?.let { add(Path.of(it)) }
            System.getenv("ProgramFiles(x86)")?.let { add(Path.of(it, "Steam")) }
            System.getenv("ProgramFiles")?.let { add(Path.of(it, "Steam")) }
            if (DesktopPlatform.linux) {
                val home = Path.of(System.getProperty("user.home"))
                add(home.resolve(".local/share/Steam"))
                add(home.resolve(".steam/steam"))
                add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"))
            }
        }
        val steamPaths = steam.flatMap { directory ->
            val index = directory.resolve("steamapps/libraryfolders.vdf")
            listOf(directory) + runCatching {
                if (!Files.isRegularFile(index)) emptyList()
                else steamLibraries.findAll(Files.readString(index))
                    .map { Path.of(it.groupValues[1].replace("\\\\", "\\")) }.toList()
            }.getOrDefault(emptyList())
        }.distinct()
        for (directory in steamPaths) {
            names += scanManifests(directory.resolve("steamapps"), "appmanifest_*.acf") { content ->
                steamTitle.find(content)?.groupValues?.get(1)
            }
        }
        System.getenv("ProgramData")?.let { programData ->
            names += scanManifests(Path.of(programData, "Epic/EpicGamesLauncher/Data/Manifests"), "*.item") { content ->
                val manifest = JSONObject(content)
                val location = manifest.optString("InstallLocation")
                manifest.optString("DisplayName").takeIf { location.isNotBlank() && Files.isDirectory(Path.of(location)) }
            }
        }
        return names.map(::gameId).toSet()
    }

    fun device(): List<DesktopField> = listOf(
        DesktopField("System", "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
        DesktopField("Architecture", System.getProperty("os.arch")),
        DesktopField("Logical processors", Runtime.getRuntime().availableProcessors().toString()),
        DesktopField("Java", System.getProperty("java.version")),
        DesktopField("Active adapters", NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }.joinToString { it.displayName }.ifBlank { "None" })
    )

    private fun readGames(text: String): List<WindowsGame> {
        val entries = JSONObject(text).objects("games").mapNotNull { row ->
            val title = row.optString("name").trim()
            if (title.isEmpty()) return@mapNotNull null
            val endpoints = row.optJSONArray("test_endpoints") ?: JSONArray()
            val hosts = (0 until endpoints.length()).map { endpoints.optString(it).trim() }
                .filter { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) }.distinct()
            WindowsGame(gameId(title), title, row.optString("category"), hosts)
        }.distinctBy(WindowsGame::id)
        require(entries.isNotEmpty()) { "The game catalog contains no game records." }
        return entries
    }

    private fun scanManifests(dir: Path, pattern: String, extract: (String) -> String?): List<String> =
        runCatching {
            if (!Files.isDirectory(dir)) return emptyList()
            Files.newDirectoryStream(dir, pattern).use { files ->
                files.mapNotNull { runCatching { extract(Files.readString(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList())

    private fun installedInventory(): JSONObject = runCatching {
        val script = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            ${'$'}roots = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
            ${'$'}apps = @(Get-ItemProperty ${'$'}roots -ErrorAction SilentlyContinue | Where-Object DisplayName | Select-Object DisplayName)
            ${'$'}steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
            @{ apps = ${'$'}apps; steam = ${'$'}steam } | ConvertTo-Json -Compress -Depth 3
        """.trimIndent()
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", script).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val read = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        try {
            require(process.waitFor(12, TimeUnit.SECONDS)) { "Game discovery timed out." }
            JSONObject(read.get(1, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrDefault(JSONObject())

    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun gameId(name: String): String = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}

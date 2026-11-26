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
internal data class InstalledDesktopGame(val title: String, val source: String, val knownOnly: Boolean = false)

internal object WindowsCatalog {
    private const val CATALOG_LIMIT = 2 * 1024 * 1024
    private val steamProperties = Regex("""(?m)^[ \t]*"([^"\r\n]+)"[ \t]+"((?:\\.|[^"\\\r\n])*)"""")
    private val steamEscape = Regex("""\\(["\\])""")
    private val steamTools = Regex("""(?i)^(?:Proton (?:\d[\w.-]*|Experimental|Hotfix|EasyAntiCheat Runtime|BattlEye Runtime)|Steam Linux Runtime.*|Steamworks Common Redistributables)$""")

    fun games(directory: Path): List<WindowsGame> {
        val override = directory.resolve("games.json")
        val saved = readLauncherFile(override)?.let { runCatching { readGames(it) }.getOrNull() }
        return saved ?: readGames(resource("games_online.json"))
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

    fun installedGames(): List<InstalledDesktopGame> {
        val inventory = if (DesktopPlatform.linux) JSONObject() else installedInventory()
        val steam = buildList {
            if (DesktopPlatform.linux) {
                val home = Path.of(System.getProperty("user.home"))
                absolutePath(System.getenv("XDG_DATA_HOME").orEmpty())?.let { add(it.resolve("Steam")) }
                add(home.resolve(".local/share/Steam"))
                add(home.resolve(".steam/steam"))
                add(home.resolve(".steam/root"))
                add(home.resolve(".steam/debian-installation"))
                add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam"))
            } else {
                val registryPaths = inventory.optJSONArray("steam") ?: JSONArray()
                for (index in 0 until registryPaths.length()) {
                    absolutePath(registryPaths.optString(index))?.let(::add)
                }
                for (variable in listOf("ProgramFiles(x86)", "ProgramFiles", "ProgramW6432")) {
                    absolutePath(System.getenv(variable).orEmpty())?.let { add(it.resolve("Steam")) }
                }
            }
        }
        val epic = if (DesktopPlatform.linux) null else absolutePath(System.getenv("ProgramData").orEmpty())
            ?.resolve("Epic/EpicGamesLauncher/Data/Manifests")
        return scanInstalledGames(steam, epic, inventory.objects("apps").map { it.optString("DisplayName") })
    }

    internal fun scanInstalledGames(
        steamRoots: List<Path>, epicManifests: Path?, registeredApps: List<String>
    ): List<InstalledDesktopGame> = buildList {
        // Modern and legacy Steam indexes both point at additional library roots.
        val libraries = steamRoots.distinct().flatMap { root ->
            val extra = readLauncherFile(root.resolve("steamapps/libraryfolders.vdf"))?.let { index ->
                steamEntries(index).filter { (key) -> key == "path" || key.all(Char::isDigit) }
                    .mapNotNull { (_, value) -> absolutePath(value) }
            }.orEmpty()
            listOf(root) + extra
        }.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }.distinct()
        for (library in libraries) {
            val steamApps = library.resolve("steamapps")
            val titles = scanManifests(steamApps, "appmanifest_*.acf") { content ->
                val manifest = steamEntries(content).toMap()
                val installed = manifest["stateflags"]?.toIntOrNull()?.let { it and 4 != 0 } ?: true
                val location = manifest["installdir"]?.let(Path::of)
                val present = location == null || (
                    !location.isAbsolute && location.nameCount == 1 && location.toString() !in setOf(".", "..") &&
                        Files.isDirectory(steamApps.resolve("common").resolve(location))
                    )
                manifest["name"]?.trim()?.takeIf {
                    installed && present && it.isNotEmpty() && !steamTools.matches(it)
                }
            }
            addAll(titles.map { InstalledDesktopGame(it, "Steam") })
        }
        if (epicManifests != null) {
            val titles = scanManifests(epicManifests, "*.item") { content ->
                val manifest = JSONObject(content)
                val location = absolutePath(manifest.optString("InstallLocation"))
                manifest.optString("DisplayName").trim().takeIf {
                    it.isNotEmpty() && !manifest.optBoolean("bIsIncompleteInstall") &&
                        location != null && Files.isDirectory(location)
                }
            }
            addAll(titles.map { InstalledDesktopGame(it, "Epic Games") })
        }
        addAll(registeredApps.map(String::trim).filter(String::isNotEmpty)
            .map { InstalledDesktopGame(it, "Windows", knownOnly = true) })
    }

    fun presentGames(catalog: List<WindowsGame>, installed: List<InstalledDesktopGame>): List<DesktopGame> {
        val catalogIds = catalog.mapTo(mutableSetOf(), WindowsGame::id)
        val installedIds = mutableSetOf<String>()
        val otherGames = linkedMapOf<String, DesktopGame>()
        for (game in installed) {
            val id = gameId(game.title)
            if (id.isEmpty()) continue
            if (id in catalogIds) {
                installedIds.add(id)
            } else if (!game.knownOnly) {
                otherGames.putIfAbsent(id, DesktopGame(id, game.title, game.source, installed = true, canTest = false))
            }
        }
        val catalogGames = catalog.map { game ->
            DesktopGame(game.id, game.title, game.category, game.id in installedIds, game.hosts.isNotEmpty())
        }
        return (catalogGames + otherGames.values)
            .sortedWith(compareByDescending<DesktopGame> { it.installed }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    fun device(adapters: () -> List<String> = ::activeAdapters): List<DesktopField> = listOf(
        DesktopField("System", "${System.getProperty("os.name")} ${System.getProperty("os.version")}"),
        DesktopField("Architecture", System.getProperty("os.arch")),
        DesktopField("Logical processors", Runtime.getRuntime().availableProcessors().toString()),
        DesktopField("Java", System.getProperty("java.version")),
        DesktopField("Active adapters", runCatching { adapters().joinToString().ifBlank { "None" } }
            .getOrDefault("Unavailable"))
    )

    private fun activeAdapters(): List<String> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .mapNotNull { adapter ->
            runCatching {
                if (adapter.isUp && !adapter.isLoopback) adapter.displayName ?: adapter.name else null
            }.getOrNull()
        }

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
            Files.newDirectoryStream(dir, pattern).use { files ->
                files.mapNotNull { file -> readLauncherFile(file)?.let { runCatching { extract(it) }.getOrNull() } }
            }
        }.getOrDefault(emptyList())

    private fun readLauncherFile(path: Path): String? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) > CATALOG_LIMIT) return null
        Files.readString(path).removePrefix("\uFEFF")
    }.getOrNull()

    private fun absolutePath(value: String): Path? = runCatching { Path.of(value).takeIf(Path::isAbsolute) }.getOrNull()

    private fun steamEntries(content: String): List<Pair<String, String>> = steamProperties.findAll(content)
        .map { entry ->
            val (_, key, value) = entry.groupValues
            key.lowercase(Locale.ROOT) to steamEscape.replace(value) { it.groupValues[1] }
        }.toList()

    private fun installedInventory(): JSONObject = runCatching {
        val script = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            ${'$'}roots = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
            ${'$'}apps = @(Get-ItemProperty ${'$'}roots -ErrorAction SilentlyContinue | Where-Object DisplayName | Select-Object DisplayName)
            ${'$'}steamKeys = 'HKCU:\Software\Valve\Steam', 'HKLM:\Software\Valve\Steam',
                'HKLM:\Software\WOW6432Node\Valve\Steam'
            ${'$'}steam = @(Get-ItemProperty ${'$'}steamKeys -ErrorAction SilentlyContinue |
                ForEach-Object { ${'$'}_.SteamPath; ${'$'}_.InstallPath } | Where-Object { ${'$'}_ })
            @{ apps = ${'$'}apps; steam = ${'$'}steam } | ConvertTo-Json -Compress -Depth 3
        """.trimIndent()
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", script).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val read = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        try {
            require(process.waitFor(12, TimeUnit.SECONDS)) { "Game discovery timed out." }
            require(process.exitValue() == 0) { "Windows game discovery failed." }
            JSONObject(read.get(1, TimeUnit.SECONDS).trim().removePrefix("\uFEFF"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrDefault(JSONObject())

    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun gameId(name: String): String = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}

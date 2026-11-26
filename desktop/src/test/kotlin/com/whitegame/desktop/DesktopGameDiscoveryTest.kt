package com.whitegame.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DesktopGameDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun findsInstalledGamesAcrossLibrariesAndKeepsTitlesMissingFromTheCatalog() {
        val steam = temp.newFolder("Steam").toPath()
        val modernLibrary = temp.newFolder("Games on D").toPath()
        val legacyLibrary = temp.newFolder("Old Library").toPath()
        Files.createDirectories(steam.resolve("steamapps"))
        Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"), """
            "libraryfolders"
            {
                "1"
                {
                    "path" "${vdfPath(modernLibrary)}"
                }
                "2" "${vdfPath(legacyLibrary)}"
            }
        """.trimIndent())
        steamGame(steam, 1, "Counter-Strike™ 2")
        steamGame(modernLibrary, 2, "Space \"Pioneers\"")
        steamGame(legacyLibrary, 3, "Old Library Game")
        steamGame(legacyLibrary, 4, "Unfinished Install", state = 2)
        steamGame(legacyLibrary, 5, "Missing Files", hasInstallDirectory = false)
        steamGame(modernLibrary, 6, "Steam Linux Runtime 3.0 (sniper)")
        steamGame(modernLibrary, 7, "Proton 10.0")
        Files.writeString(steam.resolve("steamapps/appmanifest_bad.acf"), "invalid manifest")

        val epic = temp.newFolder("Epic Manifests").toPath()
        val epicInstall = temp.newFolder("Epic Game Install").toPath()
        Files.writeString(epic.resolve("game.item"), JSONObject()
            .put("InstallLocation", epicInstall.toString()).put("DisplayName", "Epic Exclusive").toString())
        Files.writeString(epic.resolve("incomplete.item"), JSONObject().put("bIsIncompleteInstall", true)
            .put("InstallLocation", epicInstall.toString()).put("DisplayName", "Incomplete Epic Game").toString())
        Files.writeString(epic.resolve("missing.item"), JSONObject()
            .put("InstallLocation", epicInstall.resolve("missing").toString()).put("DisplayName", "Stale Epic Game").toString())

        val catalog = listOf(
            WindowsGame("counterstrike2", "Counter-Strike 2", "FPS", listOf("example.test")),
            WindowsGame("valorant", "Valorant", "FPS", listOf("example.test")),
            WindowsGame("dota2", "Dota 2", "MOBA", emptyList())
        )
        val inventory = WindowsCatalog.scanInstalledGames(listOf(steam, modernLibrary), epic, listOf("VALORANT", "Windows Utility"))
        val results = WindowsCatalog.presentGames(catalog, inventory)
        assertEquals(
            setOf("Counter-Strike 2", "Space \"Pioneers\"", "Old Library Game", "Epic Exclusive", "Valorant"),
            results.filter(DesktopGame::installed).map(DesktopGame::title).toSet()
        )
        val byTitle = results.associateBy(DesktopGame::title)
        assertEquals("Steam", byTitle.getValue("Space \"Pioneers\"").detail)
        assertFalse(byTitle.getValue("Epic Exclusive").canTest)
        assertTrue(byTitle.getValue("Counter-Strike 2").canTest)
        assertFalse(byTitle.getValue("Dota 2").installed)
        assertEquals(6, results.size)
    }

    @Test
    fun anInvalidSavedCatalogDoesNotPreventDiscovery() {
        val directory = temp.newFolder("Catalog").toPath()
        Files.writeString(directory.resolve("games.json"), "not json")

        assertTrue(WindowsCatalog.games(directory).any { it.id == "counterstrike2" })
    }

    @Test
    fun deviceDetailsStayAvailableWhenAdapterEnumerationFails() {
        val fields = WindowsCatalog.device { throw IOException("Adapters unavailable") }.associate { it.label to it.value }
        assertEquals("Unavailable", fields["Active adapters"])
        assertNotNull(fields["System"])
        assertNotNull(fields["Architecture"])
        assertNotNull(fields["Logical processors"])
    }

    private fun steamGame(steam: Path, id: Int, title: String, state: Int = 4, hasInstallDirectory: Boolean = true) {
        val apps = steam.resolve("steamapps")
        Files.createDirectories(apps)
        if (hasInstallDirectory) Files.createDirectories(apps.resolve("common/game$id"))
        val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
        Files.writeString(apps.resolve("appmanifest_$id.acf"), """
            "AppState"
            {
                "name" "$escapedTitle"
                "StateFlags" "$state"
                "installdir" "game$id"
            }
        """.trimIndent())
    }

    private fun vdfPath(path: Path): String = path.toString().replace("\\", "\\\\")
}

package com.whitegame.app.viewmodel

import com.whitegame.app.model.GameInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameMatchTest {
    private fun game(id: String, name: String) = GameInfo(id, name, "", emptyList(), emptyList(), emptyList(), emptyList(), "")
    private val catalog = listOf(game("pubg", "PUBG Mobile"), game("codm", "Call of Duty: Mobile"), game("go", "Go"))

    @Test fun matchesInstalledLabelsToCatalogNames() {
        assertEquals("pubg", matchByName("PUBG MOBILE", catalog))
        assertEquals("codm", matchByName("Call of Duty", catalog))
        assertEquals("go", matchByName("GO", catalog))
        assertNull(matchByName("Google", catalog))
        assertNull(matchByName("Solitaire", catalog))
    }
}

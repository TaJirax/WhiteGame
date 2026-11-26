package com.whitegame.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNavigationTest {
    @Test
    fun backRestoresPreviousPagesTabsAndScrollPositions() {
        val navigation = DesktopNavigation()
        assertFalse(navigation.canGoBack)

        assertTrue(navigation.open(DesktopPage.DEVICE, DesktopNetworkTab.DNS, scrollPosition = 35))
        assertTrue(navigation.open(DesktopPage.NETWORK, DesktopNetworkTab.GAMES, scrollPosition = 112))
        assertTrue(navigation.open(DesktopPage.NETWORK, DesktopNetworkTab.CONFIGS, scrollPosition = 218))
        assertEquals(0, navigation.current.scrollPosition)
        assertFalse(navigation.open(DesktopPage.NETWORK, DesktopNetworkTab.CONFIGS, scrollPosition = 9))

        assertTrue(navigation.goBack())
        assertEquals(DesktopNavigation.Location(DesktopPage.NETWORK, DesktopNetworkTab.GAMES, 218), navigation.current)
        assertTrue(navigation.goBack())
        assertEquals(DesktopNavigation.Location(DesktopPage.DEVICE, DesktopNetworkTab.DNS, 112), navigation.current)
        assertTrue(navigation.goBack())
        assertEquals(DesktopNavigation.Location(DesktopPage.CONNECT, DesktopNetworkTab.DNS, 35), navigation.current)
        assertFalse(navigation.canGoBack)
        assertFalse(navigation.goBack())
    }
}

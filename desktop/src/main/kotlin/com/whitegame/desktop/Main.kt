package com.whitegame.desktop

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        try {
            val directory = Files.createDirectories(DesktopPlatform.dataDirectory())
            val instanceFile = FileChannel.open(
                directory.resolve("whitegame.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE
            )
            try {
                val instance = checkNotNull(instanceFile.tryLock()) { "WhiteGame is already running." }
                DesktopController(DesktopStore(directory)) {
                    try { instance.release() } finally { instanceFile.close() }
                }.show()
            } catch (e: Exception) {
                instanceFile.close()
                throw e
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(null, e.message ?: "Could not start WhiteGame.", "WhiteGame", JOptionPane.ERROR_MESSAGE)
        }
    }
}

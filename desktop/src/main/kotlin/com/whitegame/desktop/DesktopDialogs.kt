package com.whitegame.desktop

import com.google.zxing.WriterException
import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.Keys
import com.whitegame.app.connection.encodeTunnelQr
import com.whitegame.app.connection.tunnelConfigFileName
import java.awt.Desktop
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

internal class DesktopDialogs(
    private val owner: JFrame,
    private val style: DesktopStyle,
    private val dispatch: (DesktopAction) -> Unit
) {
    private var currentDialog: JDialog? = null

    fun editProfile(profile: DesktopProfile? = null) {
        val name = style.field(profile?.name.orEmpty())
        val config = style.editor(profile?.config.orEmpty(), 12)
        val content = style.column(
            style.legend("Profile name"),
            name,
            style.legend("WireGuard config, WireGuard share link or Xray outbound"),
            style.scroll(config, height = 235),
            style.grid(
                style.button("WireGuard template") {
                    if (config.text.isBlank() || confirm("Replace the config text in this editor?", "Replace text")) {
                        config.text = "[Interface]\nPrivateKey = ${Keys.generatePrivateKey()}\nAddress = 10.8.0.2/32\n\n" +
                            "[Peer]\nPublicKey = SERVER_PUBLIC_KEY\nAllowedIPs = 0.0.0.0/0, ::/0\n" +
                            "Endpoint = vpn.example.com:51820\nPersistentKeepalive = 25\n"
                    }
                },
                style.button("Open file") { openConfig { _, text -> config.text = text } },
                style.button("Copy public key") {
                    val key = runCatching { AwgConfig.parse(config.text).publicKey }.getOrElse {
                        val privateKey = privateKeyLine.find(config.text)?.groupValues?.get(1)
                        privateKey?.let { runCatching { Keys.publicKeyOf(it) }.getOrNull() }
                    }
                    if (key == null) message("Add or generate a WireGuard private key first.", "Public key")
                    else copy(key)
                }
            )
        ).apply { preferredSize = Dimension(560, preferredSize.height) }
        if (confirm(content, if (profile == null) "New tunnel" else "Edit ${profile.name}", "Save")) {
            dispatch(DesktopAction.SaveProfile(name.text, config.text, profile?.id))
        }
    }

    fun openConfig(read: (String, String) -> Unit) {
        val picker = JFileChooser().apply {
            dialogTitle = "Open tunnel config"
            fileFilter = FileNameExtensionFilter("Tunnel configs and lists", "conf", "json", "txt")
        }
        if (picker.showOpenDialog(currentDialog ?: owner) != JFileChooser.APPROVE_OPTION) return
        val selected = picker.selectedFile.toPath()
        object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String = Files.readString(selected)
            override fun done() {
                val value = try {
                    get()
                } catch (_: ExecutionException) {
                    message("Could not read the selected file as UTF-8 text.", "Open config")
                    return
                }
                read(picker.selectedFile.nameWithoutExtension, value)
            }
        }.execute()
    }

    fun chooseProfile(profiles: List<DesktopProfile>, title: String, choose: (DesktopProfile) -> Unit) {
        if (profiles.isEmpty()) return
        val profilesByName = profiles.map { profile -> "${profile.name}  ·  ${profile.kind}" }.toTypedArray()
        val selected = javax.swing.JComboBox(profilesByName).apply {
            font = style.body
            background = style.inset
            foreground = style.trace
        }
        if (confirm(style.column(style.legend("Saved profiles"), selected), title, "Use profile")) {
            choose(profiles[selected.selectedIndex])
        }
    }

    fun deleteProfile(profile: DesktopProfile) {
        if (confirm("Delete ${profile.name} from this PC? Its saved private key will be removed.", "Delete tunnel")) {
            dispatch(DesktopAction.DeleteProfile(profile.id))
        }
    }

    fun enroll() {
        val url = style.field()
        val token = style.password()
        val name = style.field("Self-hosted WireGuard")
        val content = style.column(
            style.paragraph("Register this PC with your WireGuard provisioning server. The private key stays on this PC.", rows = 2),
            style.legend("Provisioning URL"),
            url,
            style.legend("Enrollment token"),
            token,
            style.legend("Profile name"),
            name
        ).apply { preferredSize = Dimension(455, preferredSize.height) }
        if (confirm(content, "Self-hosted WireGuard setup", "Create / refresh profile")) {
            val secret = token.password
            try {
                dispatch(DesktopAction.Enroll(url.text.trim(), String(secret).trim(), name.text.trim()))
            } finally {
                secret.fill('\u0000')
                token.text = ""
            }
        }
    }

    fun createWarp() {
        val terms = style.toggle("I accept Cloudflare’s terms", false) {}
        val endpointTests = style.toggle("Test Cloudflare’s endpoints during setup", true) {}
        val content = style.column(
            style.paragraph("WARP registration sends the public key to Cloudflare. The private key stays on this PC."),
            style.button("Read Cloudflare terms") {
                runCatching { Desktop.getDesktop().browse(URI("https://www.cloudflare.com/application/terms/")) }
                    .onFailure { message("Could not open the browser.", "Cloudflare terms") }
            },
            terms,
            endpointTests
        ).apply { preferredSize = Dimension(430, preferredSize.height) }
        if (!confirm(content, "WARP setup", "Create WARP profile")) return
        if (terms.isSelected) dispatch(DesktopAction.CreateWarp(endpointTests.isSelected))
        else message("Accept the Cloudflare terms to create a WARP profile.", "WARP setup")
    }

    fun export(profile: DesktopProfile) {
        val content = style.column(
            style.paragraph("The exported file and QR code contain this tunnel’s private key.", rows = 2),
            style.grid(
                style.button("Save .conf", primary = true) { saveConfig(profile) },
                style.button("Show QR code") { showQr(profile) }
            )
        ).apply { preferredSize = Dimension(430, preferredSize.height) }
        message(content, "Export ${profile.name}")
    }

    private fun saveConfig(profile: DesktopProfile) {
        val picker = JFileChooser().apply {
            dialogTitle = "Save tunnel config"
            selectedFile = java.io.File(tunnelConfigFileName(profile.name))
            fileFilter = FileNameExtensionFilter("WireGuard config", "conf")
        }
        if (picker.showSaveDialog(currentDialog ?: owner) != JFileChooser.APPROVE_OPTION) return
        val selected = picker.selectedFile
        val destination = if (selected.name.endsWith(".conf", ignoreCase = true)) selected.toPath()
            else selected.toPath().resolveSibling("${selected.name}.conf")
        if (Files.exists(destination) && !confirm("Replace ${destination.fileName}?", "Save config")) return
        runCatching { Files.writeString(destination, profile.config) }
            .onFailure { message("Could not save the selected file.", "Export config") }
    }

    private fun showQr(profile: DesktopProfile) {
        val result = try {
            encodeTunnelQr(profile.config)
        } catch (_: WriterException) {
            message("This config is too large for a QR code. Save the .conf file instead.", "Export config")
            return
        }
        val bitmap = BufferedImage(result.width, result.height, BufferedImage.TYPE_BYTE_BINARY)
        for (y in 0 until result.height) {
            for (x in 0 until result.width) bitmap.setRGB(x, y, if (result[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        val scale = maxOf(2, 330 / result.width)
        val qr = ImageIcon(bitmap.getScaledInstance(result.width * scale, result.height * scale, Image.SCALE_REPLICATE))
        message(JLabel(qr).apply {
            accessibleContext.accessibleName = "Tunnel configuration QR code"
        }, "${profile.name} QR code")
    }

    fun copy(value: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null) }
            .onFailure { message("The Windows clipboard is temporarily unavailable.", "Copy") }
    }

    fun confirm(content: Any, title: String, action: String = "Delete") = dialog(content, title, action)

    private fun message(content: Any, title: String) {
        dialog(content, title, null)
    }

    private fun dialog(content: Any, title: String, actionLabel: String?): Boolean {
        val previous = currentDialog
        val parent = previous ?: owner
        val dialog = JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL)
        var accepted = false
        val body = content as? JComponent ?: style.paragraph(content.toString(), rows = 2).apply {
            preferredSize = Dimension(410, preferredSize.height)
        }
        val accept = style.button(actionLabel ?: "Close", primary = true) {
            accepted = true
            dialog.dispose()
        }
        val footer = if (actionLabel == null) accept else style.grid(
            style.button("Cancel") { dialog.dispose() },
            accept
        )
        dialog.contentPane = style.column(
            style.label(title, style.title),
            body,
            footer,
            gap = DesktopSpace.md
        ).apply {
            background = style.ink
            isOpaque = true
            border = EmptyBorder(DesktopSpace.xl, DesktopSpace.xl, DesktopSpace.xl, DesktopSpace.xl)
        }
        dialog.rootPane.defaultButton = accept
        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        currentDialog = dialog
        try {
            dialog.isVisible = true
        } finally {
            currentDialog = previous
        }
        return accepted
    }

    private companion object {
        val privateKeyLine = Regex("(?im)^\\s*PrivateKey\\s*=\\s*([^\\s#]+)")
    }
}

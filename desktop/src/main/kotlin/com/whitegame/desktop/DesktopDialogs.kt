package com.whitegame.desktop

import com.google.zxing.WriterException
import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException
import com.whitegame.app.connection.Keys
import com.whitegame.app.connection.encodeTunnelQr
import com.whitegame.app.connection.tunnelConfigFileName
import java.awt.BorderLayout
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
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingWorker
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

internal class DesktopDialogs(
    private val owner: JFrame,
    private val style: DesktopStyle,
    private val dispatch: (DesktopAction) -> Unit
) {
    private var currentDialog: JDialog? = null

    fun editDnsResolver(resolver: DesktopDnsResolver? = null, copy: Boolean = false) {
        val name = style.field(resolver?.name.orEmpty())
        val address = style.field(resolver?.address.orEmpty())
        val id = resolver?.takeUnless { copy }?.id
        val title = if (id == null) "Add DNS resolver" else "Edit DNS resolver"
        val content = style.column(
            style.legend("Provider name"),
            name,
            style.legend("IPv4 or IPv6 address"),
            address
        )
        while (confirm(content, title, "Save")) {
            val failure = runCatching { customDnsResolver(name.text, address.text) }.exceptionOrNull()
            if (failure == null) {
                dispatch(DesktopAction.SaveDnsResolver(name.text, address.text, id))
                return
            }
            message(failure.message.orEmpty(), title)
        }
    }

    fun editProfile(profile: DesktopProfile? = null) {
        val name = style.field(profile?.name.orEmpty())
        val config = DesktopConfigEditor(
            style, profile?.config.orEmpty(), profile != null && profile.kind != "Xray"
        ) { message(it, "Config editor") }
        val content = style.column(
            style.legend("Profile name"),
            name,
            config.component,
            style.grid(
                style.button("WireGuard template") {
                    if (!config.hasDraft() || confirm("Replace the config text in this editor?", "Replace text")) {
                        config.replace("[Interface]\nPrivateKey = ${Keys.generatePrivateKey()}\nAddress = 10.8.0.2/32\n\n" +
                            "[Peer]\nPublicKey = SERVER_PUBLIC_KEY\nAllowedIPs = 0.0.0.0/0, ::/0\n" +
                            "Endpoint = vpn.example.com:51820\nPersistentKeepalive = 25\n")
                    }
                },
                style.button("Open file") { openConfig { _, text -> config.replace(text) } },
                style.button("Copy public key") {
                    val text = try {
                        config.text()
                    } catch (e: ConfigException) {
                        message(e.message ?: "Check the WireGuard fields first.", "Public key")
                        return@button
                    }
                    val key = runCatching { AwgConfig.parse(text).publicKey }.getOrElse {
                        val privateKey = privateKeyLine.find(text)?.groupValues?.get(1)
                        privateKey?.let { runCatching { Keys.publicKeyOf(it) }.getOrNull() }
                    }
                    if (key == null) message("Add or generate a WireGuard private key first.", "Public key")
                    else copy(key)
                }
            )
        ).apply { preferredSize = Dimension(560, preferredSize.height) }
        dialog(content, if (profile == null) "New tunnel" else "Edit ${profile.name}", "Save") { complete ->
            val text = try {
                config.text()
            } catch (e: ConfigException) {
                complete(e.message ?: "Could not read the config draft.")
                return@dialog
            }
            dispatch(DesktopAction.SaveProfile(name.text, text, profile?.id, complete))
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
            .onFailure { message("The clipboard is temporarily unavailable.", "Copy") }
    }

    fun confirm(content: Any, title: String, action: String = "Delete") = dialog(content, title, action)

    private fun message(content: Any, title: String) {
        dialog(content, title, null)
    }

    private fun dialog(
        content: Any,
        title: String,
        actionLabel: String?,
        submit: (((String?) -> Unit) -> Unit)? = null
    ): Boolean {
        val previous = currentDialog
        val parent = previous ?: owner
        val dialog = JDialog(parent, title, Dialog.ModalityType.APPLICATION_MODAL)
        var accepted = false
        val body = content as? JComponent ?: style.paragraph(content.toString(), rows = 2).apply {
            preferredSize = Dimension(410, preferredSize.height)
        }
        val error = submit?.let { style.paragraph("", rows = 2, foreground = style.trace) }
        var submitting = false
        val accept = actionLabel?.let { label ->
            style.button(label, primary = true) {
                if (submitting) return@button
                fun complete(message: String?) {
                    submitting = false
                    if (message == null) {
                        accepted = true
                        dialog.dispose()
                    } else {
                        error?.text = message
                        error?.caretPosition = 0
                    }
                }
                if (submit == null) {
                    complete(null)
                } else {
                    submitting = true
                    submit(::complete)
                }
            }
        }
        val back = style.button("Back", action = dialog::dispose).apply { toolTipText = "Go back (Esc)" }
        val header = style.split(style.label(title, style.title), back)
        val scroll = style.scroll(body).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        dialog.contentPane = JPanel(BorderLayout(0, DesktopSpace.md)).apply {
            background = style.ink
            border = EmptyBorder(DesktopSpace.xl, DesktopSpace.xl, DesktopSpace.xl, DesktopSpace.xl)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            if (accept != null) {
                val action = style.split(style.spacer(1), accept)
                add(if (error == null) action else style.column(error, action), BorderLayout.SOUTH)
            }
        }
        dialog.rootPane.defaultButton = accept ?: back
        dialog.rootPane.registerKeyboardAction(
            { dialog.dispose() },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.pack()
        val screen = parent.graphicsConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
        val availableWidth = screen.bounds.width - insets.left - insets.right - DesktopSpace.edge * 2
        val availableHeight = screen.bounds.height - insets.top - insets.bottom - DesktopSpace.edge * 2
        dialog.setSize(
            minOf(dialog.width, availableWidth.coerceAtLeast(1)),
            minOf(dialog.height, availableHeight.coerceAtLeast(1))
        )
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

package com.whitegame.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.SavedTunnel
import com.whitegame.app.connection.TunnelKind
import com.whitegame.app.ui.components.Chip
import com.whitegame.app.ui.components.DataRow
import com.whitegame.app.ui.components.EmptyState
import com.whitegame.app.ui.components.Field
import com.whitegame.app.ui.components.Hairline
import com.whitegame.app.ui.components.IconAction
import com.whitegame.app.ui.components.Legend
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.PrimaryButton
import com.whitegame.app.ui.components.Quality
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.StatusPill
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Inset
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.Panel as PanelColor
import com.whitegame.app.ui.theme.Shapes
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Trace3
import com.whitegame.app.ui.theme.Type
import com.whitegame.app.viewmodel.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TunnelsScreen(vm: ConnectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var confirmDelete by remember { mutableStateOf<SavedTunnel?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".conf").orEmpty()
            if (text.isNullOrBlank()) vm.importText("", name) else vm.importText(text, name)
        }
    }

    if (editing != null) {
        BackHandler {
            editing = null
            vm.clearEditorMessages()
        }
        TunnelEditor(
            target = editing!!,
            vm = vm,
            onClose = {
                editing = null
                vm.clearEditorMessages()
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.edge)
    ) {
        Spacer(Modifier.statusBarsPadding())
        VSpace(Space.sm)
        ScreenHeader(
            title = "Tunnels",
            subtitle = "WireGuard, AmneziaWG and Xray profiles"
        )
        VSpace(Space.lg)

        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton(
                "New tunnel",
                onClick = { editing = EditorTarget.New(TunnelKind.WIREGUARD) },
                icon = Icons.Outlined.Add,
                modifier = Modifier.weight(1f),
                height = 48.dp
            )
            SecondaryButton(
                "Import file",
                onClick = { fileLauncher.launch(arrayOf("*/*")) },
                icon = Icons.Outlined.FileOpen,
                modifier = Modifier.weight(1f),
                height = 48.dp
            )
        }

        AnimatedVisibility(vm.editorMessage.isNotBlank() || vm.editorError.isNotBlank()) {
            Column {
                VSpace(Space.md)
                Notice(vm.editorError.ifBlank { vm.editorMessage }, emphasis = true)
            }
        }

        VSpace(Space.xl)

        if (vm.tunnels.isEmpty()) {
            RowGroup {
                EmptyState(
                    legend = "Nothing saved",
                    message = "Paste a config from your provider, or open a .conf file. AmneziaWG profiles keep their Jc / S / H obfuscation settings."
                )
            }
        } else {
            SectionHeader("Saved", "${vm.tunnels.size} profile" + if (vm.tunnels.size == 1) "" else "s")
            VSpace(Space.sm)
            RowGroup {
                vm.tunnels.forEachIndexed { i, t ->
                    if (i > 0) Hairline()
                    TunnelRow(
                        tunnel = t,
                        selected = t.id == vm.selectedId,
                        expanded = expandedId == t.id,
                        config = remember(t.confText) { vm.parsed(t) },
                        valid = remember(t.confText) { vm.isValid(t) },
                        onSelect = { vm.select(t.id) },
                        onToggleDetail = { expandedId = if (expandedId == t.id) null else t.id },
                        onEdit = { editing = EditorTarget.Existing(t) },
                        onDelete = { confirmDelete = t },
                        onCopyPublicKey = { key ->
                            copyToClipboard(context, "Public key", key)
                        }
                    )
                }
            }
        }

        VSpace(Space.xl)
        SectionHeader("Subscription", "Your own server list")
        VSpace(Space.sm)
        Field(
            legend = "Subscription URL",
            value = vm.subUrl,
            onValueChange = { vm.updateSubUrl(it) },
            placeholder = "https://your-provider/wg-configs.txt",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        VSpace(Space.sm)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton(
                if (vm.busySub) "Loading" else "Load list",
                onClick = { vm.refreshSubscription() },
                loading = vm.busySub,
                enabled = vm.subUrl.isNotBlank(),
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.weight(1f),
                height = 46.dp
            )
            SecondaryButton(
                if (vm.busyScan) "Scanning" else "Rank by ping",
                onClick = { vm.scanSubscriptionNodes() },
                loading = vm.busyScan,
                enabled = vm.subNodes.isNotEmpty(),
                modifier = Modifier.weight(1f),
                height = 46.dp
            )
        }
        if (vm.subNodes.count { it.conf.isNotBlank() } > 1) {
            VSpace(Space.sm)
            SecondaryButton(
                "Add all " + vm.subNodes.count { it.conf.isNotBlank() } + " as tunnels",
                onClick = { vm.importAllNodes() },
                icon = Icons.Outlined.Add,
                modifier = Modifier.fillMaxWidth(),
                height = 44.dp
            )
        }
        if (vm.subUrl.isBlank()) {
            VSpace(Space.sm)
            Notice(
                "Paste your provider's link. Plain .conf text, sing-box JSON and base64 lists " +
                    "all work; servers that come with keys can be added as tunnels in one tap."
            )
        }
        if (vm.subMessage.isNotBlank()) {
            VSpace(Space.sm)
            Notice(vm.subMessage)
        }
        if (vm.subNodes.isNotEmpty()) {
            VSpace(Space.sm)
            RowGroup {
                vm.subNodes.take(12).forEachIndexed { i, n ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                n.flag + " " + n.name, color = Trace, style = Type.subtitle,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                n.host + ":" + n.port + " · " + n.region,
                                color = Trace3, style = Type.monoSm,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (n.pingMs != null) {
                            Spacer(Modifier.width(Space.sm))
                            Text(n.pingMs.toString() + "ms", color = Trace, style = Type.mono)
                        }
                        Spacer(Modifier.width(Space.sm))
                        if (n.conf.isNotBlank()) {
                            SecondaryButton(
                                "Add",
                                onClick = { vm.importNode(n) },
                                height = 36.dp,
                                modifier = Modifier.width(66.dp)
                            )
                        } else {
                            Legend("ping only")
                        }
                    }
                }
            }
        }

        VSpace(Space.xxl)
        VSpace(Space.xxl)
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = PanelColor,
            titleContentColor = Trace,
            textContentColor = Trace2,
            title = { Text("Delete " + target.name + "?", style = Type.title) },
            text = { Text("The config and its private key are removed from this device. This cannot be undone.", style = Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTunnel(target.id)
                    confirmDelete = null
                }) { Text("Delete", color = Trace, style = Type.button) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Keep", color = Trace2, style = Type.button)
                }
            }
        )
    }
}

@Composable
private fun TunnelRow(
    tunnel: SavedTunnel,
    selected: Boolean,
    expanded: Boolean,
    config: AwgConfig?,
    valid: Boolean,
    onSelect: () -> Unit,
    onToggleDetail: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyPublicKey: (String) -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleDetail)
                .padding(start = Space.lg, end = Space.sm, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (selected) Trace else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (selected) Trace else Line, CircleShape)
                    .clickable(onClick = onSelect)
            )
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tunnel.name, color = Trace, style = Type.subtitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(Space.sm))
                    StatusPill(
                        tunnel.kind.label,
                        if (!valid) Quality.Bad else Quality.Unknown
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    if (!valid) "Config no longer parses — open to fix it"
                    else tunnel.endpoint.ifBlank { "no endpoint" },
                    color = Trace3, style = Type.monoSm,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconAction(Icons.Outlined.Edit, "Edit " + tunnel.name, onEdit)
            IconAction(Icons.Outlined.Delete, "Delete " + tunnel.name, onDelete)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(bottom = Space.sm)) {
                Hairline()
                if (!valid) {
                    Notice(
                        "This profile could not be read. Open it and correct the config.",
                        Modifier.padding(horizontal = Space.lg, vertical = Space.md)
                    )
                } else if (config == null) {
                    // Valid, but the rows below are WireGuard interface fields an Xray link
                    // has no equivalent of.
                    Notice(
                        "Xray profile. Open it to view or edit the link.",
                        Modifier.padding(horizontal = Space.lg, vertical = Space.md)
                    )
                } else {
                    DataRow("Interface address", config.iface.addresses.joinToString(", ").ifBlank { "—" })
                    Hairline()
                    DataRow("DNS", config.iface.dnsServers.joinToString(", ").ifBlank { "system" })
                    Hairline()
                    DataRow("MTU", (config.iface.mtu ?: 1280).toString())
                    Hairline()
                    DataRow("Routes", config.allowedIps.joinToString(", ").ifBlank { "—" })
                    Hairline()
                    DataRow("Peers", config.peers.size.toString())
                    if (config.iface.amnezia.isNotEmpty()) {
                        Hairline()
                        DataRow(
                            "Obfuscation",
                            AwgConfig.AMNEZIA_ORDER
                                .mapNotNull { k -> config.iface.amnezia[k.lowercase()]?.let { k + "=" + it } }
                                .joinToString(" "),
                            valueStyle = Type.monoSm
                        )
                    }
                    Hairline()
                    val pub = remember(config) { runCatching { config.publicKey }.getOrNull() }
                    DataRow(
                        "Your public key",
                        pub?.take(16)?.plus("…") ?: "unavailable",
                        valueStyle = Type.monoSm,
                        onClick = { pub?.let(onCopyPublicKey) },
                        trailing = {
                            if (pub != null) {
                                Icon(Icons.Outlined.ContentCopy, "Copy public key", Modifier.size(16.dp), tint = Trace3)
                            }
                        }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- editor

sealed class EditorTarget {
    data class New(val kind: TunnelKind) : EditorTarget()
    data class Existing(val tunnel: SavedTunnel) : EditorTarget()
}

/**
 * Full-screen config editor. Validates as you type so a bad key or a malformed endpoint is
 * caught here rather than as a tunnel that silently never handshakes.
 */
@Composable
private fun TunnelEditor(
    target: EditorTarget,
    vm: ConnectionViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val existing = (target as? EditorTarget.Existing)?.tunnel

    var kind by remember {
        mutableStateOf(existing?.kind ?: (target as EditorTarget.New).kind)
    }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var conf by remember {
        mutableStateOf(existing?.confText ?: vm.templateFor(kind))
    }
    var validation by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableStateOf(existing != null) }

    LaunchedEffect(conf) {
        validation = if (touched) vm.validate(conf) else null
    }

    // Keyed on the key itself, not the whole text: deriving a public key is a full scalar
    // multiplication and must not run on every keystroke elsewhere in the config.
    val privateKeyInConf = remember(conf) {
        Regex("(?im)^\\s*PrivateKey\\s*=\\s*(\\S+)").find(conf)?.groupValues?.get(1)
    }
    val publicKey = remember(privateKeyInConf) { privateKeyInConf?.let { vm.publicKeyFor(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.edge)
    ) {
        Spacer(Modifier.statusBarsPadding())
        VSpace(Space.sm)
        ScreenHeader(
            title = if (existing == null) "New tunnel" else "Edit tunnel",
            subtitle = if (existing == null) "Paste a config, or start from a template"
            else "Changes apply the next time you connect"
        )
        VSpace(Space.lg)

        if (existing == null) {
            Legend("Start from")
            VSpace(Space.sm)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TunnelKind.values().forEach { k ->
                    Chip(k.label, kind == k, onClick = {
                        kind = k
                        conf = vm.templateFor(k)
                        touched = false
                        validation = null
                    })
                }
            }
            VSpace(Space.lg)
        }

        Field(
            legend = "Name",
            value = name,
            onValueChange = { name = it },
            placeholder = "Optional — taken from the endpoint if blank"
        )
        VSpace(Space.lg)

        Field(
            legend = "Configuration",
            value = conf,
            onValueChange = {
                conf = it
                touched = true
            },
            placeholder = "[Interface] …",
            singleLine = false,
            mono = true,
            minHeight = 260.dp,
            error = validation
        )

        VSpace(Space.sm)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton(
                "Generate key",
                onClick = {
                    val key = vm.newPrivateKey()
                    conf = if (Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(conf)) {
                        conf.replace(Regex("(?im)^(\\s*PrivateKey\\s*=\\s*).*$"), "PrivateKey = " + key)
                    } else {
                        conf.replace("[Interface]", "[Interface]\nPrivateKey = " + key)
                    }
                    touched = true
                },
                modifier = Modifier.weight(1f),
                height = 44.dp
            )
            SecondaryButton(
                "Paste",
                onClick = {
                    readClipboard(context)?.let {
                        conf = it
                        touched = true
                    }
                },
                modifier = Modifier.weight(1f),
                height = 44.dp
            )
        }

        AnimatedVisibility(publicKey != null) {
            Column {
                VSpace(Space.md)
                RowGroup {
                    DataRow(
                        "Your public key",
                        publicKey.orEmpty(),
                        valueStyle = Type.monoSm,
                        onClick = { publicKey?.let { copyToClipboard(context, "Public key", it) } },
                        trailing = {
                            Icon(Icons.Outlined.ContentCopy, "Copy public key", Modifier.size(16.dp), tint = Trace3)
                        }
                    )
                    Hairline()
                    Box(Modifier.padding(horizontal = Space.lg, vertical = 10.dp)) {
                        Text(
                            "Give this key to your server so it can recognise this device.",
                            color = Trace3, style = Type.small
                        )
                    }
                }
            }
        }

        if (kind == TunnelKind.AMNEZIA || existing?.kind == TunnelKind.AMNEZIA) {
            VSpace(Space.lg)
            AmneziaHelp()
        }

        AnimatedVisibility(vm.editorError.isNotBlank()) {
            Column {
                VSpace(Space.md)
                Notice(vm.editorError, emphasis = true)
            }
        }

        VSpace(Space.xl)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton("Cancel", onClick = onClose, modifier = Modifier.weight(1f))
            PrimaryButton(
                if (existing == null) "Save tunnel" else "Save changes",
                onClick = {
                    if (vm.saveTunnel(name, conf, existing?.id)) onClose()
                },
                enabled = validation == null && conf.isNotBlank(),
                modifier = Modifier.weight(1f)
            )
        }

        VSpace(Space.xxl)
        VSpace(Space.xxl)
    }
}

/** AmneziaWG's fields are unusual enough to be worth explaining where they are edited. */
@Composable
private fun AmneziaHelp() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.inset)
            .background(Inset)
            .border(1.dp, Line, Shapes.inset)
            .padding(Space.lg)
    ) {
        Legend("AmneziaWG 3.1 fields")
        VSpace(Space.sm)
        listOf(
            "Jc / Jmin / Jmax" to "How many junk packets to send before a handshake, and their size range.",
            "S1–S4" to "Packet padding for handshake, cookie and transport packets.",
            "H1–H4" to "Packet-type numbers or ranges. H1?H4 must not overlap; use the exact server values.",
            "I1–I5" to "Optional signature packets sent on connect.",
            "3.1 settings" to "HeaderProtectionKey, ContentPaddingAddition, timing ranges, RandomTrailers and DisableCookies are supported. Copy values from your server config.",
            "Keepalive" to "PersistentKeepalive accepts a number or a range, such as 22-30."
        ).forEach { (field, meaning) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(field, color = Trace2, style = Type.monoSm, modifier = Modifier.width(110.dp))
                Text(meaning, color = Trace3, style = Type.small, modifier = Modifier.weight(1f))
            }
        }
        VSpace(Space.sm)
        Text(
            "Leave them out entirely for a plain WireGuard tunnel — the same engine handles both.",
            color = Trace3, style = Type.small
        )
    }
}

// ---------------------------------------------------------------- clipboard

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

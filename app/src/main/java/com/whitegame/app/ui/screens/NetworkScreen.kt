package com.whitegame.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whitegame.app.model.DnsItem
import com.whitegame.app.model.GameInfo
import com.whitegame.app.model.ProxyNode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.whitegame.app.ui.components.Chip
import com.whitegame.app.ui.components.DataRow
import com.whitegame.app.ui.components.EmptyState
import com.whitegame.app.ui.components.Field
import com.whitegame.app.ui.components.Hairline
import com.whitegame.app.ui.components.IconAction
import com.whitegame.app.ui.components.Legend
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.PrimaryButton
import com.whitegame.app.ui.components.ProgressStrip
import com.whitegame.app.ui.components.Quality
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.Segmented
import com.whitegame.app.ui.components.StatusPill
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Inset
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.Shapes
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Trace3
import com.whitegame.app.ui.theme.Type
import com.whitegame.app.viewmodel.ConnectionViewModel
import com.whitegame.app.viewmodel.DnsViewModel
import com.whitegame.app.viewmodel.GamesViewModel
import com.whitegame.app.viewmodel.TestViewModel

private val SECTIONS = listOf("DNS", "Configs", "Games")

@Composable
fun NetworkScreen() {
    var section by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Space.edge)) {
            Spacer(Modifier.statusBarsPadding())
            VSpace(Space.sm)
            ScreenHeader(
                title = "Network",
                subtitle = "Measure the path your games actually take"
            )
            VSpace(Space.lg)
            Segmented(SECTIONS, section, onSelect = { section = it })
            VSpace(Space.md)
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
            label = "networkSection"
        ) { s ->
            when (s) {
                0 -> DnsSection()
                1 -> ConfigsSection()
                else -> GamesSection()
            }
        }
    }
}

// ---------------------------------------------------------------- DNS

@Composable
private fun DnsSection(
    vm: DnsViewModel = hiltViewModel(),
    connectionVm: ConnectionViewModel = hiltViewModel()
) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { connectionVm.onVpnPermissionResult(it.resultCode) }
    val rows = remember(vm.rows, vm.scanning) {
        vm.rows.sortedWith(compareByDescending<DnsItem> { it.score }.thenBy { it.pingMs ?: Long.MAX_VALUE })
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Space.edge, end = Space.edge, bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item { DnsOverridePanel(connectionVm, launcher) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (vm.scanning) {
                    SecondaryButton(
                        "Stop", onClick = { vm.stopScan() },
                        icon = Icons.Outlined.Stop, modifier = Modifier.weight(1f), height = 48.dp
                    )
                } else {
                    PrimaryButton(
                        "Test all", onClick = { vm.startScan() },
                        icon = Icons.Outlined.NetworkCheck, modifier = Modifier.weight(1f), height = 48.dp
                    )
                }
                SecondaryButton(
                    if (vm.updating) "Updating" else "Update list",
                    onClick = { vm.updateDnsList() },
                    loading = vm.updating,
                    modifier = Modifier.weight(1f), height = 48.dp
                )
            }
        }
        item {
            SecondaryButton(
                "Save results", onClick = { vm.saveCurrent() },
                enabled = vm.rows.any { it.pingMs != null },
                modifier = Modifier.fillMaxWidth(), height = 44.dp
            )
        }
        item { ProgressStrip(vm.scanning, vm.progress.ifBlank { vm.updateMessage }) }
        if (vm.saveMessage.isNotBlank()) item { Notice(vm.saveMessage) }

        if (vm.nearest.isNotEmpty()) {
            item {
                VSpace(Space.sm)
                SectionHeader("Best for logins", "Lowest latency once jitter and loss are counted")
            }
            item {
                RowGroup {
                    vm.nearest.take(5).forEachIndexed { i, d ->
                        if (i > 0) Hairline()
                        BestDnsRow(
                            item = d,
                            active = connectionVm.systemDns == d.ip || (connectionVm.isConnected && connectionVm.preferredDns == d.ip),
                            onUse = { connectionVm.toggleDns(d.ip, launcher) }
                        )
                    }
                }
            }
        }

        item {
            VSpace(Space.sm)
            SectionHeader("All servers", "${vm.rows.size} in the list")
        }
        if (rows.isEmpty()) {
            item { RowGroup { EmptyState("Empty", "No DNS servers loaded. Tap Update list to fetch the catalog.") } }
        } else {
            itemsIndexed(rows, key = { _, d -> d.ip }) { _, d ->
                DnsCard(
                    d = d,
                    active = connectionVm.systemDns == d.ip || (connectionVm.isConnected && connectionVm.preferredDns == d.ip),
                    onUse = { connectionVm.toggleDns(d.ip, launcher) }
                )
            }
        }
    }
}

@Composable
private fun BestDnsRow(item: DnsItem, active: Boolean, onUse: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.provider, color = Trace, style = Type.subtitle, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(item.ip, color = Trace3, style = Type.monoSm)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = Space.md)) {
            Text((item.pingMs ?: 0).toString() + "ms", color = Trace, style = Type.mono)
            Legend("±" + (item.jitterMs ?: 0) + " · " + (item.lossPct ?: 0) + "%")
        }
        SecondaryButton(
            if (active) "In use" else "Use",
            onClick = onUse,
            height = 38.dp,
            modifier = Modifier.width(78.dp)
        )
    }
}

/** The DNS the tunnel will use, settable from a list entry or typed by hand. */
@Composable
private fun DnsOverridePanel(vm: ConnectionViewModel, launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>) {
    var custom by remember { mutableStateOf("") }
    RowGroup {
        DataRow("DNS active", vm.systemDns ?: if (vm.isConnected) (vm.preferredDns ?: "Tunnel DNS") else "Android default")
        Hairline()
        Column(Modifier.padding(Space.lg)) {
            Field(
                legend = "Set any DNS",
                value = custom,
                onValueChange = { custom = it },
                placeholder = "1.1.1.1 or 178.22.122.100, 185.51.200.2",
                mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PrimaryButton(
                    "Apply",
                    onClick = {
                        vm.usePreferredDns(custom, launcher)
                        custom = ""
                    },
                    enabled = custom.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
                SecondaryButton(
                    if (vm.isConnected) "Tunnel DNS" else "Android default",
                    onClick = { vm.usePreferredDns(null, launcher) },
                    enabled = vm.preferredDns != null,
                    modifier = Modifier.weight(1f),
                    height = 44.dp
                )
            }
            if (vm.dnsMessage.isNotBlank()) {
                Spacer(Modifier.height(Space.sm))
                Notice(vm.dnsMessage)
            }
        }
    }
}

@Composable
private fun DnsCard(d: DnsItem, active: Boolean, onUse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.panel)
            .background(com.whitegame.app.ui.theme.Panel)
            .border(1.dp, Line, Shapes.panel)
            .padding(Space.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.provider, color = Trace, style = Type.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(d.ip, color = Trace3, style = Type.monoSm)
            }
            if (d.pingMs != null) {
                StatusPill(
                    d.status,
                    when {
                        d.score >= 75 -> Quality.Good
                        d.score >= 45 -> Quality.Caution
                        else -> Quality.Bad
                    }
                )
                Spacer(Modifier.width(Space.md))
                Text(d.pingMs.toString() + "ms", color = Trace, style = Type.mono.copy(fontSize = 16.sp))
            } else {
                Legend("Not tested")
            }
            Spacer(Modifier.width(Space.md))
            SecondaryButton(
                if (active) "In use" else "Use",
                onClick = onUse,
                height = 36.dp,
                modifier = Modifier.width(74.dp)
            )
        }
        if (d.pingMs != null && d.gameAdvice.isNotBlank()) {
            Spacer(Modifier.height(Space.sm))
            Text(
                "jitter ±" + (d.jitterMs ?: 0) + "ms · loss " + (d.lossPct ?: 0) + "% · " + d.gameAdvice,
                color = Trace3, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------- configs

@Composable
private fun ConfigsSection(vm: TestViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val saved by vm.savedConfigs.collectAsStateWithLifecycle()
    var chooseSaved by remember { mutableStateOf(false) }
    if (chooseSaved) androidx.compose.material3.AlertDialog(
        onDismissRequest = { chooseSaved = false },
        title = { Text("Load a saved config") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(saved, key = { it.id }) { config ->
                    SecondaryButton(config.name, onClick = {
                        vm.onTextChange(config.confText)
                        chooseSaved = false
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { chooseSaved = false }) { Text("Close") } }
    )
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Space.edge, end = Space.edge, bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        if (saved.isNotEmpty()) item {
            SecondaryButton("Load saved config", onClick = { chooseSaved = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        }
        item {
            Field(
                legend = "Config text",
                value = vm.text,
                onValueChange = { vm.onTextChange(it) },
                placeholder = "Paste VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / TUIC links, a JSON outbound, or a WireGuard config",
                singleLine = false,
                mono = true,
                minHeight = 150.dp,
                trailing = {
                    IconAction(Icons.Outlined.ContentCopy, "Paste from clipboard", {
                        readClipboard(context)?.let { vm.onTextChange(it) }
                    })
                }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (vm.busy) {
                    SecondaryButton(
                        "Stop", onClick = { vm.stop() }, icon = Icons.Outlined.Stop,
                        modifier = Modifier.weight(1f), height = 48.dp
                    )
                } else {
                    PrimaryButton(
                        "Test configs", onClick = { vm.startTest() }, icon = Icons.Outlined.PlayArrow,
                        modifier = Modifier.weight(1f), height = 48.dp
                    )
                }
                SecondaryButton(
                    "Save", onClick = { vm.saveCurrent() },
                    enabled = vm.nodes.any { it.reachable != null },
                    modifier = Modifier.width(96.dp), height = 48.dp
                )
            }
        }
        item { ProgressStrip(vm.busy, vm.status) }
        if (vm.saveMessage.isNotBlank()) item { Notice(vm.saveMessage) }

        if (vm.nodes.isEmpty()) {
            item {
                RowGroup {
                    EmptyState(
                        "Nothing tested yet",
                        "Paste one or more configs above, then run the path test to see authenticated Xray HTTPS latency, or endpoint reachability for other config types."
                    )
                }
            }
        } else {
            items(vm.nodes, key = { it.protocol + ":" + it.host + ":" + it.port + ":" + it.index }) { n ->
                ProxyCard(n) {
                    copyToClipboard(context, "Config report", n.caption.ifBlank { n.detail })
                }
            }
        }
    }
}

@Composable
private fun ProxyCard(n: ProxyNode, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.panel)
            .background(com.whitegame.app.ui.theme.Panel)
            .border(1.dp, Line, Shapes.panel)
            .padding(Space.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (n.serverFlag.ifBlank { "" } + " " + n.protocol).trim(),
                color = Trace, style = Type.subtitle
            )
            Spacer(Modifier.weight(1f))
            if (n.reachable != null) {
                StatusPill(
                    when (n.dropRisk) {
                        "Low" -> "Stable"
                        "Medium" -> "Some risk"
                        "High" -> "Unstable"
                        "Critical" -> "Unusable"
                        else -> if (n.reachable == true) "Reachable" else "Unreachable"
                    },
                    when (n.dropRisk) {
                        "Low" -> Quality.Good
                        "Medium" -> Quality.Caution
                        "High", "Critical" -> Quality.Bad
                        else -> if (n.reachable == true) Quality.Good else Quality.Bad
                    }
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(n.name, color = Trace2, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(n.host + ":" + n.port, color = Trace3, style = Type.monoSm)

        if (n.reachable != null) {
            Spacer(Modifier.height(Space.md))
            ProbeMetrics(n.tcpMs, n.tcpJitter, n.tcpLoss,
                latencyLabel = if (n.tlsMs != null) "HTTPS delay" else "TCP delay")
            VSpace(Space.sm)
            Text("UDP: " + n.udpStatus.ifBlank { "Not tested" }, color = Trace3, style = Type.small)
            if (n.serverRegion.isNotBlank()) {
                Spacer(Modifier.height(Space.md))
                Text("Server region · " + n.serverFlag + " " + n.serverRegion, color = Trace2, style = Type.small)
            }
            if (n.dropAdvice.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(n.dropAdvice, color = Trace3, style = Type.small, maxLines = 3)
            }
            if (n.caption.isNotBlank()) {
                Spacer(Modifier.height(Space.md))
                SecondaryButton("Copy report", onClick = onCopy, modifier = Modifier.fillMaxWidth(), height = 42.dp)
            }
        }
    }
}

@Composable
private fun MiniMetric(legend: String, value: String, unit: String) {
    Column {
        Legend(legend)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Trace, style = Type.mono)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(unit, color = Trace3, style = Type.monoSm)
            }
        }
    }
}

/** Full-width names and evenly sized metric columns also fit compact phones. */
@Composable
private fun ProbeMetrics(latency: Long?, jitter: Long?, loss: Int?, latencyLabel: String = "TLS delay") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        listOf(latencyLabel to (latency?.let { "$it ms" } ?: "--"),
            "Jitter" to (jitter?.let { "$it ms" } ?: "--"),
            "Probe loss" to (loss?.let { "$it%" } ?: "--")).forEach { (label, value) ->
            Column(Modifier.weight(1f).clip(Shapes.inset).background(Inset).padding(10.dp)) {
                Text(label, color = Trace3, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(value, color = Trace, style = Type.mono.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ComparisonRouteCard(row: GamesViewModel.RouteRow) {
    val stats = row.stats
    RowGroup {
        Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(row.label, modifier = Modifier.fillMaxWidth(), color = Trace, style = Type.subtitle,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.error != null) Text(row.error, color = Trace3, style = Type.small)
            else {
                ProbeMetrics(stats.latencyMs, stats.jitterMs, stats.lossPct)
                Text(when {
                    stats.attempts == 0 -> "Waiting for first response"
                    stats.successful == 0 -> "No response from this endpoint | ${stats.attempts} probes"
                    else -> "${stats.successful} of ${stats.attempts} probes answered"
                }, color = Trace3, style = Type.small)
            }
        }
    }
}

// ---------------------------------------------------------------- games

@Composable
private fun GamesSection(vm: GamesViewModel = hiltViewModel()) {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.rescan(quiet = true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.stopComparison()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.stopComparison() }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(vm.comparisonGame) {
        if (vm.comparisonGame != null) listState.animateScrollToItem(4)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogUrl by rememberSaveable { mutableStateOf(vm.catalogUrl) }
    var showCatalogSettings by rememberSaveable { mutableStateOf(false) }
    val base = if (vm.showInstalledOnly) vm.games.filter { it.id in vm.installedIds } else vm.games
    val filtered = base.filter {
        query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Space.edge, end = Space.edge, bottom = 120.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Shapes.inset)
                    .background(Inset)
                    .border(1.dp, Line, Shapes.inset)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Trace3)
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search games", color = Trace3, style = Type.body)
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Type.body.copy(color = Trace),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Trace),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Chip("All titles", !vm.showInstalledOnly, onClick = { if (vm.showInstalledOnly) vm.toggleInstalledOnly() })
                Chip("My games (${vm.deviceGames.size})", vm.showInstalledOnly, onClick = { if (!vm.showInstalledOnly) vm.toggleInstalledOnly() })
                Spacer(Modifier.weight(1f))
                SecondaryButton(
                    if (vm.updating) "…" else "Update",
                    onClick = { vm.updateGamesList() },
                    loading = vm.updating,
                    height = 36.dp,
                    modifier = Modifier.width(92.dp)
                )
            }
        }
        item { ProgressStrip(vm.scanning || vm.updating, vm.updateMessage) }
        item { Text("Compare game endpoints across DNS and saved configs. TLS delay estimates connection setup, not gameplay ping. Probe loss counts failed tests, not UDP packets.", style = Type.small, color = Trace3) }
        if (vm.comparisonGame != null) {
            item {
                RowGroup {
                    Column(Modifier.fillMaxWidth().padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.comparisonGame.orEmpty(), modifier = Modifier.weight(1f), style = Type.subtitle, color = Trace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(Space.sm))
                            StatusPill(if (vm.comparing) "Live" else "Stopped", if (vm.comparing) Quality.Good else Quality.Caution)
                        }
                        Text(vm.recommendedRoute, style = Type.body, color = Trace)
                        Text(vm.comparisonMessage, style = Type.small, color = Trace3)
                        SecondaryButton(if (vm.comparing) "Stop comparison" else "Dismiss results",
                            onClick = { if (vm.comparing) vm.stopComparison() else vm.dismissComparison() },
                            modifier = Modifier.fillMaxWidth(), height = 44.dp)
                    }
                }
            }
            itemsIndexed(vm.comparisons, key = { i, _ -> "route:$i" }) { _, row ->
                ComparisonRouteCard(row)
            }
        }

        item {
            SecondaryButton(if (showCatalogSettings) "Hide catalog settings" else "Catalog settings", onClick = { showCatalogSettings = !showCatalogSettings }, modifier = Modifier.fillMaxWidth())
            if (showCatalogSettings) Column {
                Field(legend = "Optional games catalog API", value = catalogUrl, onValueChange = { catalogUrl = it },
                    placeholder = "https://your-server/games.json", modifier = Modifier.fillMaxWidth())
                SecondaryButton("Save URL", onClick = { vm.updateCatalogUrl(catalogUrl) }, enabled = !vm.updating, modifier = Modifier.fillMaxWidth())
                Text("Leave blank for Google Play lookups and the built-in catalog. A custom URL must return WhiteGame games JSON.", style = Type.small, color = Trace3)
            }
        }
        val unknown = if (vm.showInstalledOnly) vm.deviceGames.filter {
            it.catalogId == null && (query.isBlank() || it.label.contains(query, true))
        } else emptyList()

        if (filtered.isEmpty() && unknown.isEmpty()) {
            item {
                RowGroup {
                    EmptyState(
                        "No matches",
                        if (vm.showInstalledOnly) "No games found on this device. Install one, then tap Rescan."
                        else "No game matches \"" + query + "\"."
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { g ->
                GameCard(
                    game = g,
                    installed = g.id in vm.installedIds,
                    busy = vm.busyId == g.id,
                    enabled = !vm.comparing,
                    result = vm.results[g.id],
                    onTest = { vm.compareGame(g) }
                )
            }
            if (unknown.isNotEmpty()) {
                item {
                    VSpace(Space.sm)
                    SectionHeader("Also on this device", "Not in the catalog yet, so there are no servers to test")
                }
                item {
                    RowGroup {
                        unknown.forEachIndexed { i, d ->
                            if (i > 0) Hairline()
                            Column(Modifier.padding(Space.md)) {
                                Text(d.label, style = Type.subtitle, color = Trace)
                                Text(d.source.label + " | " + d.packageName, style = Type.small, color = Trace3)
                                SecondaryButton("Check Google Play", onClick = { vm.lookUp(d.packageName) },
                                    loading = vm.lookupPackage == d.packageName, enabled = vm.lookupPackage == null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    game: GameInfo,
    installed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    result: Pair<String, String>?,
    onTest: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.panel)
            .background(com.whitegame.app.ui.theme.Panel)
            .border(1.dp, Line, Shapes.panel)
            .padding(Space.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        game.name, color = Trace, style = Type.subtitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (installed) {
                        Spacer(Modifier.width(Space.sm))
                        StatusPill("Installed", Quality.Good)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    game.category + " · " + game.regions.joinToString("/") + " · needs " + game.pingNeed,
                    color = Trace3, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Space.md))
            SecondaryButton(
                "Live", onClick = onTest, loading = busy, enabled = enabled,
                height = 40.dp, modifier = Modifier.width(84.dp)
            )
        }
        if (result != null) {
            Spacer(Modifier.height(Space.md))
            Hairline(inset = 0.dp)
            Spacer(Modifier.height(Space.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    result.first,
                    when (result.first) {
                        "Excellent", "Good" -> Quality.Good
                        "Playable" -> Quality.Caution
                        else -> Quality.Bad
                    }
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    result.second, color = Trace3, style = Type.small,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

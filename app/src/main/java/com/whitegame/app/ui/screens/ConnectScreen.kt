package com.whitegame.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.connection.SavedTunnel
import com.whitegame.app.connection.WhiteGameVpnService
import com.whitegame.app.model.TunnelState
import com.whitegame.app.ui.components.DataRow
import com.whitegame.app.ui.components.EmptyState
import com.whitegame.app.ui.components.Hairline
import com.whitegame.app.ui.components.Instrument
import com.whitegame.app.ui.components.LatencyTrace
import com.whitegame.app.ui.components.Legend
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.PrimaryButton
import com.whitegame.app.ui.components.Quality
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.StatusPill
import com.whitegame.app.ui.components.ToggleRow
import com.whitegame.app.ui.components.TraceMode
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.components.rememberTraceHistory
import com.whitegame.app.ui.theme.Inset
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.LocalAnimationsEnabled
import com.whitegame.app.ui.theme.Shapes
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Trace3
import com.whitegame.app.ui.theme.Type
import com.whitegame.app.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(
    onOpenTunnels: () -> Unit = {},
    vm: ConnectionViewModel = hiltViewModel()
) {
    val history = rememberTraceHistory()
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> vm.onVpnPermissionResult(result.resultCode) }

    val connected = vm.isConnected
    var pickerOpen by remember { mutableStateOf(false) }

    // Feed the trace at a steady rate so its horizontal axis stays a real time axis.
    LaunchedEffect(connected) {
        if (!connected) {
            history.clear()
            return@LaunchedEffect
        }
        while (true) {
            history.push(vm.latencyMs)
            delay(2000)
        }
    }

    // Uptime has to tick on its own; nothing else changes once a tunnel settles.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.connectedSince) {
        while (vm.connectedSince != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
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
            title = "White²",
            subtitle = "WireGuard | AmneziaWG | Xray",
            trailing = { EngineBadge(vm.engineAvailable, vm.engineVersion) }
        )
        VSpace(Space.lg)

        StateInstrument(vm, history, now)
        VSpace(Space.md)

        if (vm.tunnels.isEmpty()) {
            RowGroup {
                EmptyState(
                    legend = "No tunnels yet",
                    message = "Add a WireGuard, AmneziaWG or Xray config to connect. You can paste one, or import a .conf file.",
                    action = {
                        PrimaryButton(
                            "Add a tunnel",
                            onClick = onOpenTunnels,
                            icon = Icons.Outlined.Add,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg)
                        )
                    }
                )
            }
        } else {
            ConnectControl(vm, vpnLauncher = { vm.toggle(vpnLauncher) })
            VSpace(Space.md)
            TunnelSelector(
                selected = vm.selected,
                count = vm.tunnels.size,
                onClick = { pickerOpen = !pickerOpen }
            )
            AnimatedVisibility(
                visible = pickerOpen,
                enter = fadeIn(tween(140)) + expandVertically(tween(180)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140))
            ) {
                Column {
                    VSpace(Space.sm)
                    RowGroup {
                        vm.tunnels.forEachIndexed { i, t ->
                            if (i > 0) Hairline()
                            TunnelPickRow(
                                tunnel = t,
                                selected = t.id == vm.selectedId,
                                onClick = {
                                    vm.select(t.id)
                                    pickerOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (!vm.engineAvailable && vm.selected?.isXray != true) {
            VSpace(Space.md)
            Notice(
                "The tunnel engine did not load on this device (" +
                    (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI") +
                    "), so connecting is unavailable. Reinstalling the app usually restores it.",
                emphasis = true
            )
        }

        AnimatedVisibility(vm.errorText.isNotBlank()) {
            Column {
                VSpace(Space.md)
                Notice(vm.errorText, emphasis = true)
            }
        }

        VSpace(Space.xl)
        SectionHeader("Network optimizer", "Measure first, then choose a route")
        VSpace(Space.sm)
        SecondaryButton(if (vm.busyScan) "Measuring network..." else "Analyze network", onClick = { vm.scanIspAndDns() },
            loading = vm.busyScan, enabled = !vm.busyScan, modifier = Modifier.fillMaxWidth())
        vm.isp?.let { report ->
            VSpace(Space.sm)
            RowGroup {
                DataRow("Average connection", (report.avgPingMs?.toString() ?: "?") + " ms")
                DataRow("Failed attempts", "${report.lossPct}%")
                Column(Modifier.padding(Space.md)) { Notice(report.advice) }
            }
        }
        vm.bestDns.firstOrNull()?.let { dns ->
            VSpace(Space.sm)
            SecondaryButton("Apply recommended DNS | " + dns.ip,
                onClick = { vm.usePreferredDns(dns.ip, vpnLauncher) }, modifier = Modifier.fillMaxWidth())
        }
        if (vm.dnsMessage.isNotBlank()) Notice(vm.dnsMessage)
        VSpace(Space.xl)
        SectionHeader("Session", if (connected) "Live counters from the tunnel engine" else "Starts when a tunnel comes up")
        VSpace(Space.sm)
        SessionRows(vm)

        VSpace(Space.xl)
        SectionHeader("Behaviour", "Applies to the next connection")
        VSpace(Space.sm)
        RowGroup {
            ToggleRow(
                title = "Block traffic when the tunnel drops",
                description = "Nothing leaves the device until the tunnel is back",
                checked = vm.killSwitch,
                onCheckedChange = { vm.updateKillSwitch(it) }
            )
            Hairline()
            ToggleRow(
                title = "Reconnect automatically",
                description = "Retries up to 3 times when the handshake goes stale",
                checked = vm.autoReconnect,
                onCheckedChange = { vm.updateAutoReconnect(it) }
            )
        }

        VSpace(Space.xxl)
        VSpace(Space.xxl)
    }
}

/** Engine build, so a user reporting a problem can say which one they have. */
@Composable
private fun EngineBadge(available: Boolean, version: String) {
    Column(horizontalAlignment = Alignment.End) {
        StatusPill(
            if (available) "Engine ready" else "Engine down",
            if (available) Quality.Good else Quality.Bad
        )
        Spacer(Modifier.height(4.dp))
        Text(version, color = Trace3, style = Type.monoSm, maxLines = 1)
    }
}

/**
 * The instrument: state word, the measured number, and the trace. This is the one place the
 * app raises its voice.
 */
@Composable
private fun StateInstrument(
    vm: ConnectionViewModel,
    history: com.whitegame.app.ui.components.TraceHistory,
    now: Long
) {
    val state = vm.state
    val mode = when {
        state == TunnelState.CONNECTED -> TraceMode.Live
        state.isBusy -> TraceMode.Sweeping
        else -> TraceMode.Idle
    }

    Instrument(Modifier.fillMaxWidth(), padding = Space.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(state)
            Spacer(Modifier.width(Space.sm))
            Legend(
                when (state) {
                    TunnelState.CONNECTED -> "Connected"
                    TunnelState.CONNECTING -> "Connecting"
                    TunnelState.RECONNECTING -> "Reconnecting"
                    TunnelState.FAILED -> "Failed"
                    else -> "Not connected"
                },
                color = if (state == TunnelState.CONNECTED) Trace else Trace2
            )
            Spacer(Modifier.weight(1f))
            vm.activeTunnel?.let {
                Text(
                    it.kind.label, color = Trace3, style = Type.monoSm,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        VSpace(Space.md)

        Row(verticalAlignment = Alignment.Bottom) {
            if (state == TunnelState.CONNECTED) {
                Text(vm.latencyMs?.toString() ?: "--", color = Trace, style = Type.readout)
                Spacer(Modifier.width(6.dp))
                Text("ms", color = Trace3, style = Type.mono, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Legend("Uptime")
                    Spacer(Modifier.height(2.dp))
                    Text(uptimeText(vm.connectedSince, now), color = Trace, style = Type.mono)
                }
            } else {
                // The legend above already names the state, so this slot carries what is
                // useful instead: which tunnel is armed, and what the engine last said.
                Column {
                    Text(
                        vm.selected?.name ?: "No tunnel selected",
                        color = Trace, style = Type.title,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        vm.statusLine, color = Trace2, style = Type.small,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        VSpace(Space.md)
        LatencyTrace(history, mode)
        VSpace(Space.sm)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(if (mode == TraceMode.Live) "Last 96 s" else "Latency", color = Trace3)
            Spacer(Modifier.weight(1f))
            if (mode == TraceMode.Live) {
                Legend("min ${history.best ?: "--"}  avg ${history.average ?: "--"}  max ${history.worst ?: "--"}")
            }
        }
    }
}

@Composable
private fun StateDot(state: TunnelState) {
    val animate = LocalAnimationsEnabled.current
    val busy = state.isBusy
    val color = when (state) {
        TunnelState.CONNECTED -> Trace
        TunnelState.FAILED -> Trace3
        else -> Trace2
    }
    if (busy && animate) {
        val a by rememberInfiniteTransition(label = "dot").animateFloat(
            0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotAlpha"
        )
        Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(color))
    } else {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    }
}

/** One control that always says exactly what it will do. */
@Composable
private fun ConnectControl(vm: ConnectionViewModel, vpnLauncher: () -> Unit) {
    val live = vm.state.isLive
    if (live) {
        SecondaryButton(
            text = if (vm.isConnected) "Disconnect" else "Cancel",
            onClick = vpnLauncher,
            icon = Icons.Outlined.PowerSettingsNew,
            loading = false,
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp
        )
    } else {
        PrimaryButton(
            text = "Connect",
            onClick = vpnLauncher,
            icon = Icons.Outlined.Bolt,
            enabled = vm.selectedId != null && vm.engineAvailable,
            modifier = Modifier.fillMaxWidth(),
            height = 56.dp
        )
    }
}

@Composable
private fun TunnelSelector(selected: SavedTunnel?, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.inset)
            .background(Inset)
            .border(1.dp, Line, Shapes.inset)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Legend("Tunnel")
            Spacer(Modifier.height(3.dp))
            Text(
                selected?.name ?: "None selected",
                color = Trace, style = Type.subtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (selected != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    selected.kind.label + " · " + selected.endpoint.ifBlank { "no endpoint" },
                    color = Trace3, style = Type.monoSm,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text("$count", color = Trace3, style = Type.mono)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.ExpandMore, "Choose a tunnel", Modifier.size(20.dp), tint = Trace2)
    }
}

@Composable
private fun TunnelPickRow(tunnel: SavedTunnel, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape)
                .background(if (selected) Trace else Color.Transparent)
                .border(1.dp, if (selected) Trace else Line, CircleShape)
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(tunnel.name, color = Trace, style = Type.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                tunnel.kind.label + " · " + (if (tunnel.isFullTunnel) "all traffic" else "split"),
                color = Trace3, style = Type.monoSm
            )
        }
    }
}

@Composable
private fun SessionRows(vm: ConnectionViewModel) {
    val s = vm.stats
    RowGroup {
        DataRow(
            "Handshake",
            when {
                !s.hasHandshake -> "none yet"
                else -> (s.handshakeAgeSec ?: 0).toString() + "s ago"
            },
            valueColor = if (s.isHealthy) Trace else Trace2
        )
        Hairline()
        DataRow("Downloaded", WhiteGameVpnService.formatBytes(s.rxBytes))
        Hairline()
        DataRow("Uploaded", WhiteGameVpnService.formatBytes(s.txBytes))
        Hairline()
        DataRow(
            "Throughput",
            WhiteGameVpnService.formatBytes(s.rxBps) + "/s down · " +
                WhiteGameVpnService.formatBytes(s.txBps) + "/s up"
        )
        Hairline()
        DataRow("Endpoint", vm.activeTunnel?.endpoint?.ifBlank { "—" } ?: "—")
        Hairline()
        DataRow("Routing", if (vm.activeTunnel?.isFullTunnel == true) "All traffic" else "Split by AllowedIPs")
        Hairline()
        DataRow("DNS override", vm.preferredDns ?: "Tunnel's own")
    }
}

private fun uptimeText(since: Long?, now: Long): String {
    if (since == null) return "--:--"
    val total = ((now - since) / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
    else String.format("%02d:%02d", m, sec)
}

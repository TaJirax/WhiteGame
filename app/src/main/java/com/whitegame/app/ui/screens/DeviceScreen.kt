package com.whitegame.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.model.DeviceReport
import com.whitegame.app.network.NetworkProber
import com.whitegame.app.ui.components.DataRow
import com.whitegame.app.ui.components.EmptyState
import com.whitegame.app.ui.components.Hairline
import com.whitegame.app.ui.components.Legend
import com.whitegame.app.ui.components.PrimaryButton
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.Segmented
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Trace3
import com.whitegame.app.ui.theme.Type
import com.whitegame.app.viewmodel.ConnectionViewModel
import com.whitegame.app.viewmodel.DnsViewModel
import com.whitegame.app.viewmodel.TestViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SECTIONS = listOf("Report", "History")

@Composable
fun DeviceScreen() {
    var section by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Space.edge)) {
            Spacer(Modifier.statusBarsPadding())
            VSpace(Space.sm)
            ScreenHeader(
                title = "Device",
                subtitle = "Hardware, network path and saved scans"
            )
            VSpace(Space.lg)
            Segmented(SECTIONS, section, onSelect = { section = it })
            VSpace(Space.md)
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
            label = "deviceSection"
        ) { s ->
            if (s == 0) ReportSection() else HistorySection()
        }
    }
}

@Composable
private fun ReportSection(vm: ConnectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DeviceReport?>(null) }
    var loading by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.edge, end = Space.edge, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            PrimaryButton(
                text = if (loading) "Measuring" else "Run device scan",
                onClick = {
                    if (loading) return@PrimaryButton
                    loading = true
                    scope.launch {
                        report = withContext(Dispatchers.IO) { buildDeviceReport(context) }
                        loading = false
                    }
                },
                loading = loading,
                icon = Icons.Outlined.PhoneAndroid,
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp
            )
        }

        val r = report
        if (r == null) {
            item {
                RowGroup {
                    EmptyState(
                        "Not measured yet",
                        "Reads your phone's specs and samples the live path to three public resolvers, so you can tell a device problem from a network one."
                    )
                }
            }
        } else {
            item {
                VSpace(Space.sm)
                SectionHeader("Path", r.networkType + " · " + r.isp)
            }
            item {
                RowGroup {
                    DataRow("Live ping", (r.livePingMs?.toString() ?: "--") + " ms")
                    Hairline()
                    DataRow("Average ping", (r.avgPingMs?.toString() ?: "--") + " ms · " + r.latencyLabel)
                    Hairline()
                    DataRow(
                        "Samples",
                        r.pingSamples.joinToString(" ").ifBlank { "—" },
                        valueStyle = Type.monoSm
                    )
                }
            }
            item {
                VSpace(Space.sm)
                SectionHeader("Hardware")
            }
            item {
                RowGroup {
                    r.lines.forEachIndexed { i, (k, v) ->
                        if (i > 0) Hairline()
                        DataRow(k, v, valueStyle = Type.monoSm)
                    }
                }
            }
            item {
                VSpace(Space.sm)
                SectionHeader("What this means")
            }
            item {
                RowGroup {
                    Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.advice.forEach { line ->
                            Row {
                                Text("—", color = Trace3, style = Type.small, modifier = Modifier.width(18.dp))
                                Text(line, color = Trace2, style = Type.small)
                            }
                        }
                    }
                }
            }
        }

        item {
            VSpace(Space.lg)
            SectionHeader("Engine", "Bundled userspace tunnel")
        }
        item {
            RowGroup {
                DataRow("Status", if (vm.engineAvailable) "Loaded" else "Unavailable")
                Hairline()
                DataRow("Build", vm.engineVersion, valueStyle = Type.monoSm)
                Hairline()
                DataRow("Protocols", "WireGuard · AmneziaWG")
                Hairline()
                DataRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", valueStyle = Type.monoSm)
            }
        }
    }
}

@Composable
private fun HistorySection(
    dnsVm: DnsViewModel = hiltViewModel(),
    testVm: TestViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale.US) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        dnsVm.refreshHistory()
        testVm.refreshHistory()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.edge, end = Space.edge, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item { Segmented(listOf("DNS scans", "Config scans"), tab, onSelect = { tab = it }) }

        if (tab == 0) {
            val list = dnsVm.savedSnapshots
            if (list.isEmpty()) {
                item {
                    RowGroup {
                        EmptyState("No saved DNS scans", "Run a DNS test on the Network tab and tap Save results to keep it here.")
                    }
                }
            } else {
                items(list, key = { it.id }) { snap ->
                    val text = buildString {
                        appendLine(snap.title)
                        appendLine(fmt.format(Date(snap.timestamp)))
                        snap.items.filter { it.pingMs != null }.sortedBy { it.pingMs }.forEach { d ->
                            appendLine(d.provider + " " + d.ip + " · " + d.pingMs + "ms ±" + (d.jitterMs ?: 0) + " loss " + (d.lossPct ?: 0) + "% · " + d.status)
                        }
                    }
                    HistoryCard(
                        title = snap.title,
                        meta = fmt.format(Date(snap.timestamp)) + " · " + snap.items.count { it.pingMs != null } + " results",
                        onCopy = { copyToClipboard(context, "DNS scan", text) },
                        onLoad = { dnsVm.loadSnapshot(snap.id) },
                        onDelete = { dnsVm.deleteSnapshot(snap.id) }
                    )
                }
            }
        } else {
            val list = testVm.savedSnapshots
            if (list.isEmpty()) {
                item {
                    RowGroup {
                        EmptyState("No saved config scans", "Run a path test on the Network tab and tap Save to keep it here.")
                    }
                }
            } else {
                items(list, key = { it.id }) { snap ->
                    val text = snap.nodes.joinToString("\n\n") {
                        it.caption.ifBlank { it.protocol + " " + it.host + ":" + it.port }
                    }
                    HistoryCard(
                        title = snap.title,
                        meta = fmt.format(Date(snap.timestamp)) + " · " +
                            snap.nodes.count { it.reachable == true } + "/" + snap.nodes.size + " reachable",
                        onCopy = { copyToClipboard(context, "Config scan", text) },
                        onLoad = { testVm.loadSnapshot(snap.id) },
                        onDelete = { testVm.deleteSnapshot(snap.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    title: String,
    meta: String,
    onCopy: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    RowGroup {
        Column(Modifier.padding(Space.lg)) {
            Text(title, color = Trace, style = Type.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Legend(meta)
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SecondaryButton("Copy", onCopy, Modifier.weight(1f), height = 40.dp)
                SecondaryButton("Load", onLoad, Modifier.weight(1f), height = 40.dp)
                SecondaryButton("Delete", onDelete, Modifier.weight(1f), height = 40.dp)
            }
        }
    }
}

/**
 * Blocking: reads hardware counters and samples three public resolvers. Call from an IO
 * dispatcher.
 */
fun buildDeviceReport(ctx: Context): DeviceReport {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val totalRamGb = mem.totalMem / (1024.0 * 1024 * 1024)
    val cores = Runtime.getRuntime().availableProcessors()
    val model = Build.MANUFACTURER + " " + Build.MODEL
    val stat = StatFs(Environment.getDataDirectory().path)
    val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
    val totalGb = stat.totalBytes / (1024.0 * 1024 * 1024)
    val dm = ctx.resources.displayMetrics

    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val networkType = when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }
    val ispHint = when {
        caps == null -> "No active network"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Measured inside the tunnel"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi, ISP via your router"
        else -> "Mobile carrier"
    }

    val (samples, loss) = kotlinx.coroutines.runBlocking { NetworkProber.livePingSuite(8) }
    val live = samples.lastOrNull()
    val avg = if (samples.isNotEmpty()) samples.sum() / samples.size else null
    val latencyLabel = when {
        avg == null -> "no samples"
        avg < 40 -> "excellent"
        avg < 70 -> "good"
        avg < 120 -> "fair"
        else -> "high"
    }

    val lines = listOf(
        "Model" to model,
        "Android" to "API " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")",
        "CPU cores" to cores.toString(),
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(" "),
        "RAM" to String.format(Locale.US, "%.1f GB", totalRamGb),
        "Storage" to String.format(Locale.US, "%.0f of %.0f GB free", freeGb, totalGb),
        "Screen" to dm.widthPixels.toString() + "x" + dm.heightPixels + " @ " + dm.densityDpi + "dpi"
    )

    val advice = mutableListOf<String>()
    advice += when {
        totalRamGb >= 6 && cores >= 6 -> "Hardware handles high settings in current mobile titles."
        totalRamGb >= 4 -> "Hardware is fine on medium settings."
        else -> "Low memory — close background apps before a ranked match."
    }
    advice += "Path is " + networkType.lowercase() + " with " + (avg?.toString() ?: "--") +
        "ms average and " + loss + "% loss."
    if ((avg ?: 0) > 100) advice += "Base latency is high before any tunnel — prefer a nearby server region."
    if (loss >= 20) advice += "Packets are being dropped on this path; expect rubber-banding."
    if (loss < 5 && (avg ?: 999) < 70) advice += "This path is good enough for competitive play as-is."

    return DeviceReport(lines, advice, ispHint, networkType, live, avg, samples, latencyLabel)
}

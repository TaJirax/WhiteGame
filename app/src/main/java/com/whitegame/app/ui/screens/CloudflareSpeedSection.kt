package com.whitegame.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.ui.components.DataRow
import com.whitegame.app.ui.components.Hairline
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.PrimaryButton
import com.whitegame.app.ui.components.ProgressStrip
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.viewmodel.SpeedTestViewModel
import java.text.NumberFormat

@Composable
internal fun CloudflareSpeedSection(vm: SpeedTestViewModel = hiltViewModel()) {
    val result = vm.result
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.edge, end = Space.edge, bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item { SectionHeader("Cloudflare Speed Test") }
        item { Notice("Uses up to 18 MB of test data and shares your IP address with Cloudflare.") }
        item { Notice("When Xray is connected, the test uses the connection outside the Xray tunnel.") }
        item {
            if (vm.busy) {
                SecondaryButton(
                    "Stop test", vm::stop, Modifier.fillMaxWidth(), icon = Icons.Outlined.Stop
                )
            } else {
                PrimaryButton(
                    "Run speed test", vm::start, Modifier.fillMaxWidth(), icon = Icons.Outlined.NetworkCheck
                )
            }
        }
        item { ProgressStrip(vm.busy, vm.status) }
        item {
            RowGroup {
                DataRow("Download", formatMetric(result.downloadMbps, "Mbps"))
                Hairline()
                DataRow("Upload", formatMetric(result.uploadMbps, "Mbps"))
                Hairline()
                DataRow("Latency", formatMetric(result.latencyMs, "ms"))
                Hairline()
                DataRow("Jitter", formatMetric(result.jitterMs, "ms"))
            }
        }
    }
}

internal fun formatMetric(value: Double?, unit: String): String {
    if (value == null) return "—"
    val format = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2
    }
    return "${format.format(value)} $unit"
}

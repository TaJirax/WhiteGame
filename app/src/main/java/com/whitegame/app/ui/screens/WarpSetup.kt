package com.whitegame.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import com.whitegame.app.ui.components.Field
import com.whitegame.app.ui.components.Notice
import com.whitegame.app.ui.components.RowGroup
import com.whitegame.app.ui.components.SecondaryButton
import com.whitegame.app.ui.components.SectionHeader
import com.whitegame.app.ui.components.ToggleRow
import com.whitegame.app.ui.components.VSpace
import com.whitegame.app.ui.theme.Space
import com.whitegame.app.ui.theme.Trace2
import com.whitegame.app.ui.theme.Type
import com.whitegame.app.viewmodel.WarpViewModel

@Composable
fun WarpSetup(vm: WarpViewModel = hiltViewModel()) {
    var expanded by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    SecondaryButton("WARP setup / endpoint scan", onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth())
    if (!expanded) return
    Column {
        VSpace(Space.md)
        SectionHeader("WARP", "Create a profile or test your own endpoints")
        VSpace(Space.sm)
        Notice("Keys are generated on this phone. Registration sends your public key to Cloudflare. Availability depends on your network.")
        RowGroup {
            ToggleRow("Accept Cloudflare terms", "Required only to create a WARP profile", accepted, { accepted = it }, enabled = !vm.busy)
        }
        TextButton(onClick = { uriHandler.openUri("https://www.cloudflare.com/application/terms/") }) {
            Text("Read Cloudflare terms", style = Type.small, color = Trace2)
        }
        SecondaryButton("Create WARP profile", onClick = { vm.create(accepted) }, enabled = accepted && !vm.busy, modifier = Modifier.fillMaxWidth())
        VSpace(Space.md)
        Field("Endpoints · one host:port per line", vm.endpointsText, vm::updateEndpoints,
            placeholder = "Uses the selected profile's endpoint when empty", mono = true, singleLine = false, modifier = Modifier.fillMaxWidth())
        VSpace(Space.sm)
        Notice("Select a standard WireGuard profile above and disconnect the active tunnel. Each test completes HTTPS inside its UDP tunnel; the result measures setup time, not gameplay ping. AmneziaWG profiles must be connected to test.")
        VSpace(Space.sm)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton(if (vm.busy) "Testing…" else "Scan endpoints", onClick = vm::scan, enabled = !vm.busy, modifier = Modifier.weight(1f))
            SecondaryButton("Use best", onClick = vm::useBest, enabled = !vm.busy && vm.measurements.any { it.ms != null }, modifier = Modifier.weight(1f))
        }
        if (vm.busy && !vm.registering) TextButton(onClick = vm::stop) { Text("Stop", color = Trace2, style = Type.small) }
        if (vm.message.isNotBlank()) { VSpace(Space.sm); Notice(vm.message, emphasis = true) }
        vm.measurements.forEach { Notice(it.endpoint + " · " + (it.ms?.let { ms -> "${ms}ms" } ?: it.error.ifBlank { "No tunnel response" })) }
    }
}

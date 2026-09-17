package com.whitegame.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.whitegame.app.ui.components.IconAction
import com.whitegame.app.ui.components.Legend
import com.whitegame.app.ui.theme.Ink
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.ThemeMode
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace3

private enum class Destination(val label: String, val icon: ImageVector) {
    Connect("Connect", Icons.Outlined.Bolt),
    Tunnels("Tunnels", Icons.Outlined.Layers),
    Network("Network", Icons.Outlined.Insights),
    Device("Device", Icons.Outlined.PhoneAndroid)
}

@Composable
fun Root() {
    var destination by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    val current = runCatching { Destination.valueOf(destination) }.getOrDefault(Destination.Connect)

    Scaffold(
        containerColor = Ink,
        bottomBar = { NavBar(current) { destination = it.name } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (fadeIn(tween(160)) + slideInVertically { it / 24 }) togetherWith
                        fadeOut(tween(110))
                },
                label = "destination"
            ) { screen ->
                when (screen) {
                    Destination.Connect -> ConnectScreen(
                        onOpenTunnels = { destination = Destination.Tunnels.name }
                    )
                    Destination.Tunnels -> TunnelsScreen()
                    Destination.Network -> NetworkScreen()
                    Destination.Device -> DeviceScreen()
                }
            }
        }
    }
}

/**
 * Channel selector, not a pill bar: the active destination is marked by a rule above it, the
 * way a hardware input selector lights the chosen channel.
 */
@Composable
private fun NavBar(current: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ink)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Destination.values().forEach { d ->
                NavItem(d, d == current) { onSelect(d) }
            }
        }
    }
}

@Composable
private fun NavItem(destination: Destination, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Trace else Trace3
    Column(
        Modifier
            .width(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) Trace else androidx.compose.ui.graphics.Color.Transparent)
        )
        Spacer(Modifier.height(9.dp))
        Icon(destination.icon, null, Modifier.size(21.dp), tint = tint)
        Spacer(Modifier.height(5.dp))
        Legend(destination.label, color = tint)
    }
}

/** Shared page frame: a title block over scrollable content, with the app's edge margin. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Trace, style = com.whitegame.app.ui.theme.Type.display)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Trace3, style = com.whitegame.app.ui.theme.Type.small)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        val context = LocalContext.current
        IconAction(
            if (ThemeMode.dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            if (ThemeMode.dark) "Switch to day mode" else "Switch to dark mode",
            onClick = { ThemeMode.toggle(context) }
        )
    }
}

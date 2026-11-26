package com.whitegame.desktop

internal fun interface DesktopActions {
    fun dispatch(action: DesktopAction)
}

internal sealed interface DesktopAction {
    data class Theme(val dark: Boolean) : DesktopAction
    data class SelectProfile(val id: String) : DesktopAction
    data class SaveProfile @JvmOverloads constructor(
        val name: String,
        val config: String,
        val id: String? = null,
        val complete: ((String?) -> Unit)? = null
    ) : DesktopAction
    data class ImportProfiles(val config: String, val name: String = "") : DesktopAction
    data class DeleteProfile(val id: String) : DesktopAction
    data object Connect : DesktopAction
    data object Disconnect : DesktopAction
    data class AutoReconnect(val enabled: Boolean) : DesktopAction
    data class KillSwitch(val enabled: Boolean) : DesktopAction
    data object AnalyzeNetwork : DesktopAction
    data object SpeedTest : DesktopAction
    data class TestConfigs(val config: String) : DesktopAction
    data class TestDns(val extraResolvers: String = "", val resolverId: String? = null) : DesktopAction
    data class SaveDnsResolver(val name: String, val address: String, val id: String? = null) : DesktopAction
    data class DeleteDnsResolver(val id: String) : DesktopAction
    data class ApplyDns(val address: String?) : DesktopAction
    data object DiscoverGames : DesktopAction
    data class TestGame(val id: String) : DesktopAction
    data class CompareGame(val id: String) : DesktopAction
    data class UpdateCatalog(val url: String) : DesktopAction
    data class CreateWarp(val testEndpoints: Boolean) : DesktopAction
    data class ScanEndpoints(val endpoints: String) : DesktopAction
    data class UseEndpoint(val endpoint: String) : DesktopAction
    data class Enroll(val url: String, val token: String, val name: String) : DesktopAction
    data class LoadSubscription(val url: String) : DesktopAction
    data object RankSubscription : DesktopAction
    data class AddSubscriptionProfile(val id: String) : DesktopAction
    data object AddAllSubscriptionProfiles : DesktopAction
    data object InspectDevice : DesktopAction
    data class SaveReport(val kind: DesktopReportKind) : DesktopAction
    data class LoadReport(val id: String) : DesktopAction
    data class DeleteReport(val id: String) : DesktopAction
    data object Stop : DesktopAction
    data object Close : DesktopAction
}

internal enum class DesktopPhase(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting"),
    DISCONNECTING("Disconnecting"),
    FAILED("Failed");

    val isLive: Boolean get() = this == CONNECTING || this == CONNECTED || this == RECONNECTING
}

internal enum class DesktopReportKind(val label: String) {
    DNS("DNS"), CONFIGS("Config tests"), GAMES("Game tests"), SPEED("Speed test")
}

internal data class DesktopField(val label: String, val value: String)

internal data class DesktopProfile(
    val id: String,
    val name: String,
    val kind: String,
    val endpoint: String,
    val config: String,
    val fullTunnel: Boolean
)

internal data class DesktopMeasurement(
    val id: String,
    val name: String,
    val endpoint: String = "",
    val protocol: String = "",
    val latencyMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPercent: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val detail: String = "",
    val status: String = "",
    val canAdd: Boolean = false
)

internal data class DesktopGame(
    val id: String,
    val title: String,
    val detail: String = "",
    val installed: Boolean = false,
    val canTest: Boolean = true
)

internal data class DesktopReport(
    val id: String,
    val kind: DesktopReportKind,
    val title: String,
    val rows: List<DesktopMeasurement>
)

internal data class DesktopState(
    val dark: Boolean = true,
    val profiles: List<DesktopProfile> = emptyList(),
    val selectedId: String? = null,
    val activeId: String? = null,
    val phase: DesktopPhase = DesktopPhase.DISCONNECTED,
    val connectionMessage: String = "Not connected",
    val recoveryRequired: Boolean = false,
    val busy: String? = null,
    val notice: String = "",
    val connectionSince: Long? = null,
    val latencyHistory: List<Double?> = emptyList(),
    val session: List<DesktopField> = emptyList(),
    val engineSummary: String = "",
    val autoReconnect: Boolean = true,
    val killSwitch: Boolean = false,
    val preferredDns: String? = null,
    val optimizer: DesktopMeasurement? = null,
    val suggestedDns: DesktopMeasurement? = null,
    val resolvers: List<DesktopDnsResolver> = emptyList(),
    val dns: List<DesktopMeasurement> = emptyList(),
    val configTests: List<DesktopMeasurement> = emptyList(),
    val games: List<DesktopGame> = emptyList(),
    val gamesScanned: Boolean = false,
    val gameTests: List<DesktopMeasurement> = emptyList(),
    val speed: DesktopMeasurement? = null,
    val endpointTests: List<DesktopMeasurement> = emptyList(),
    val subscription: List<DesktopMeasurement> = emptyList(),
    val subscriptionUrl: String = "",
    val catalogUrl: String = "",
    val device: List<DesktopField> = emptyList(),
    val devicePath: List<DesktopField> = emptyList(),
    val engines: List<DesktopField> = emptyList(),
    val reports: List<DesktopReport> = emptyList()
)

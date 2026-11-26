package com.whitegame.desktop

import com.whitegame.app.model.DnsItem
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

internal data class DesktopDnsResolver(
    val id: String,
    val name: String,
    val address: String,
    val notes: String = "",
    val custom: Boolean = false
) {
    val addressKey: String = normalizedDnsAddress(address) ?: address
}

internal fun DesktopMeasurement.savedDnsResolver(): DesktopDnsResolver? {
    val ip = normalizedDnsAddress(endpoint) ?: return null
    return DesktopDnsResolver("report-$ip", name, endpoint, detail)
}

internal fun normalizedDnsAddress(input: String): String? {
    val address = input.trim()
    if (':' in address) {
        if (!address.matches(Regex("[0-9A-Fa-f:.]+"))) return null
        return runCatching { (InetAddress.getByName(address) as? Inet6Address)?.hostAddress }.getOrNull()
    }
    val octets = address.split('.')
    if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } }) {
        return null
    }
    val values = octets.map(String::toInt)
    return values.takeIf { list -> list.all { it in 0..255 } }?.joinToString(".")
}

internal fun customDnsResolver(
    name: String,
    address: String,
    id: String = UUID.randomUUID().toString()
): DesktopDnsResolver {
    val label = name.trim()
    require(label.isNotEmpty() && label.length <= 80) { "Enter a resolver name of up to 80 characters." }
    require(normalizedDnsAddress(address) != null) { "Enter a numeric IPv4 or IPv6 resolver address." }
    return DesktopDnsResolver(id, label, address.trim(), custom = true)
}

internal fun desktopDnsCatalog(bundled: List<DnsItem>): List<DesktopDnsResolver> =
    bundled.mapIndexed { index, row ->
        DesktopDnsResolver("bundled-$index", row.provider, row.ip,
            listOf(row.notes, row.regionHint).filter(String::isNotBlank).joinToString(" · "))
    } + ipv6Resolvers.mapIndexed { index, (name, address, notes) ->
        DesktopDnsResolver("ipv6-$index", name, address, notes)
    }

internal fun List<DesktopDnsResolver>.dnsTargets(): List<DnsItem> =
    distinctBy(DesktopDnsResolver::addressKey).mapIndexed { index, resolver ->
        DnsItem(index, resolver.addressKey, resolver.name, resolver.notes)
    }

// Provider references:
// https://developers.cloudflare.com/1.1.1.1/ip-addresses/
// https://developers.google.com/speed/public-dns/docs/using/
// https://docs.quad9.net/services/
private val ipv6Resolvers = listOf(
    Triple("Cloudflare", "2606:4700:4700::1111", "Standard · IPv6 primary"),
    Triple("Cloudflare", "2606:4700:4700::1001", "Standard · IPv6 secondary"),
    Triple("Cloudflare Security", "2606:4700:4700::1112", "Malware blocking · IPv6 primary"),
    Triple("Cloudflare Security", "2606:4700:4700::1002", "Malware blocking · IPv6 secondary"),
    Triple("Cloudflare Family", "2606:4700:4700::1113", "Malware and adult content blocking · IPv6 primary"),
    Triple("Cloudflare Family", "2606:4700:4700::1003", "Malware and adult content blocking · IPv6 secondary"),
    Triple("Google Public DNS", "2001:4860:4860::8888", "IPv6 primary"),
    Triple("Google Public DNS", "2001:4860:4860::8844", "IPv6 secondary"),
    Triple("Quad9", "2620:fe::fe", "Threat blocking · IPv6 primary"),
    Triple("Quad9", "2620:fe::9", "Threat blocking · IPv6 secondary"),
    Triple("Quad9 Unfiltered", "2620:fe::10", "Without threat blocking · IPv6 primary"),
    Triple("Quad9 Unfiltered", "2620:fe::fe:10", "Without threat blocking · IPv6 secondary"),
    Triple("Quad9 ECS", "2620:fe::11", "Threat blocking, EDNS Client Subnet · IPv6 primary"),
    Triple("Quad9 ECS", "2620:fe::fe:11", "Threat blocking, EDNS Client Subnet · IPv6 secondary")
)

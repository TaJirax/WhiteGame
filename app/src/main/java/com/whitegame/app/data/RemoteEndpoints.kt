package com.whitegame.app.data

/**
 * Remote catalog URLs for live DNS / Games updates.
 *
 * DNS comes from the public-dns-directory project (refreshed twice daily):
 *   https://github.com/trybyteful/public-dns-directory
 * Per-country files are small and locally relevant, so the update picks the file for the
 * device's country and falls back to the global minimal list when there is no such file.
 *
 * Accepted shapes:
 * - dns:   { "resolvers": [ { "ip", "organization", "domain", "country_code", "uptime": {...} } ] }
 *          or the app's own [ { "id", "ip", "provider", "notes", "regionHint", "gaming" } ]
 * - games: { "games": [ { "name", "category", "tcp_ports", "udp_ports", "platforms",
 *            "test_endpoints" } ] } or the app's own [ { "id", "name", "hosts", ... } ]
 */
object RemoteEndpoints {
    private const val DNS_BASE =
        "https://raw.githubusercontent.com/trybyteful/public-dns-directory/main/data"

    /** Resolvers for one country, e.g. .../by-country/ir.json */
    fun dnsUrlForCountry(code: String): String = DNS_BASE + "/by-country/" + code.lowercase() + ".json"

    /** Every resolver, ip/country/trusted only — used when the country has no file. */
    const val DNS_GLOBAL_URL: String = "$DNS_BASE/resolvers-minimal.json"

    // No hosted catalog has been configured. Use the bundled catalog until one is supplied.
    var gamesUrl: String = ""

    /**
     * Google Play details page, read for installed apps the catalog does not know: its genre
     * says whether the app is a game, and its publisher domains become test hosts.
     */
    fun playStoreDetails(packageName: String): String =
        "https://play.google.com/store/apps/details?id=" + packageName + "&hl=en&gl=US"

    const val CONNECT_TIMEOUT_MS = 12_000
    const val READ_TIMEOUT_MS = 20_000
}

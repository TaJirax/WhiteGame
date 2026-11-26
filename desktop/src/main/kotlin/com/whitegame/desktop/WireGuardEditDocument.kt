package com.whitegame.desktop

import com.whitegame.app.connection.AwgConfig
import com.whitegame.app.connection.ConfigException

internal class WireGuardEditDocument private constructor(
    private val lines: List<String>,
    val sections: List<Section>
) {
    data class Field(val key: String, val value: String) {
        val secret: Boolean
            get() = key in setOf("PrivateKey", "PresharedKey", "HeaderProtectionKey")
    }

    data class Section(
        val title: String,
        val fields: List<Field>,
        val start: Int,
        val end: Int
    )

    /** Update only edited keys. Keep raw comments, other peers, and provider-specific lines. */
    fun render(values: List<Map<String, String>>): String {
        require(values.size == sections.size)
        val result = mutableListOf<String>()
        var from = 0
        sections.zip(values).forEach { (section, entered) ->
            result += lines.subList(from, section.start + 1)
            val changes = linkedMapOf<String, Pair<String, String>>()
            for (field in section.fields) {
                val value = entered[field.key]?.trim() ?: continue
                if (value != field.value) {
                    if (value.isEmpty() && field.key in setOf("PrivateKey", "Address", "PublicKey")) {
                        throw ConfigException("${section.title} requires ${field.key}.")
                    }
                    if (value.any { it == '\r' || it == '\n' }) {
                        throw ConfigException("${field.key} must fit on one line.")
                    }
                    changes[field.key.lowercase()] = field.key to value
                }
            }
            val body = lines.subList(section.start + 1, section.end)
            val last = body.indexOfLast(String::isNotBlank) + 1
            val written = mutableSetOf<String>()
            body.take(last).forEach { line ->
                val key = keyLine.find(line)?.groupValues?.get(2)?.lowercase().orEmpty()
                val change = changes[key]
                if (change == null) {
                    result += line
                } else {
                    val prefix = line.takeWhile(Char::isWhitespace)
                    val comment = commentOn(line)
                    if (written.add(key) && change.second.isNotEmpty()) {
                        val suffix = if (comment.isEmpty()) "" else " $comment"
                        result += "$prefix${change.first} = ${change.second}$suffix"
                    } else if (comment.isNotEmpty()) {
                        result += prefix + comment
                    }
                }
            }
            changes.forEach { (key, entry) ->
                if (key !in written && entry.second.isNotEmpty()) result += "${entry.first} = ${entry.second}"
            }
            result += body.drop(last)
            from = section.end
        }
        result += lines.drop(from)
        return result.joinToString("\n")
    }

    companion object {
        private val header = Regex("""^\s*\[([^\]]+)]\s*(?:[#;].*)?$""")
        private val keyLine = Regex("""^(\s*)([A-Za-z][A-Za-z0-9]*)\s*=""")

        fun parse(input: String): WireGuardEditDocument {
            val config = AwgConfig.parse(input)
            val text = if (AwgConfig.isShareLink(input)) config.toConfText() else input
            val lines = text.lines()
            val markers = lines.mapIndexedNotNull { i, line ->
                header.matchEntire(line)?.groupValues?.get(1)?.trim()?.lowercase()?.let { i to it }
            }
            if (markers.count { it.second == "interface" } != 1 || markers.count { it.second == "peer" } != config.peers.size) {
                throw ConfigException("This profile's sections can be edited in Config text.")
            }
            var nextPeer = 0
            val sections = markers.mapIndexedNotNull { index, (start, name) ->
                val end = markers.getOrNull(index + 1)?.first ?: lines.size
                val reserved = declaredReserved(lines.subList(start + 1, end))
                when (name) {
                    "interface" -> Section("Interface", interfaceFields(config.iface, reserved), start, end)
                    "peer" -> {
                        val peer = config.peers[nextPeer++]
                        Section("Peer $nextPeer", peerFields(peer, reserved), start, end)
                    }
                    else -> null
                }
            }
            return WireGuardEditDocument(lines, sections)
        }

        private fun interfaceFields(config: AwgConfig.Iface, reserved: String): List<Field> = buildList {
            add(Field("PrivateKey", config.privateKey))
            add(Field("Address", config.addresses.joinToString(", ")))
            add(Field("DNS", (config.dnsServers + config.dnsSearchDomains).joinToString(", ")))
            add(Field("MTU", config.mtu?.toString().orEmpty()))
            add(Field("ListenPort", config.listenPort?.toString().orEmpty()))
            if (reserved.isNotEmpty()) add(Field("Reserved", reserved))
            if (config.includedApplications.isNotEmpty()) {
                add(Field("IncludedApplications", config.includedApplications.joinToString(", ")))
            }
            if (config.excludedApplications.isNotEmpty()) {
                add(Field("ExcludedApplications", config.excludedApplications.joinToString(", ")))
            }
            if (config.amnezia.isNotEmpty()) {
                AwgConfig.AMNEZIA_ORDER.forEach { key -> add(Field(key, config.amnezia[key.lowercase()].orEmpty())) }
            }
        }

        private fun peerFields(peer: AwgConfig.Peer, reserved: String): List<Field> = listOf(
            Field("PublicKey", peer.publicKey),
            Field("PresharedKey", peer.presharedKey.orEmpty()),
            Field("Endpoint", peer.endpoint.orEmpty()),
            Field("AllowedIPs", peer.allowedIps.joinToString(", ")),
            Field("PersistentKeepalive", peer.persistentKeepalive.orEmpty()),
            Field("Reserved", reserved)
        )

        private fun declaredReserved(lines: List<String>): String = lines.lastOrNull { line ->
            keyLine.find(line)?.groupValues?.get(2).equals("Reserved", ignoreCase = true)
        }?.let { it.dropLast(commentOn(it).length).substringAfter('=').trim() }.orEmpty()

        // Amnezia packet expressions may contain # or ; as literal bytes inside a tag.
        private fun commentOn(line: String): String {
            var inTag = false
            for ((index, char) in line.withIndex()) {
                when (char) {
                    '<' -> inTag = true
                    '>' -> inTag = false
                    '#', ';' -> if (!inTag) return line.substring(index)
                }
            }
            return ""
        }
    }
}

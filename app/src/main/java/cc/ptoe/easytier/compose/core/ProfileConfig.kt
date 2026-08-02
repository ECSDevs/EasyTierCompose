package cc.ptoe.easytier.compose.core

import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.transport.root.RootTunSpec
import java.net.InetAddress

fun interface NativeConfigParser {
    /** Returns null when the document is valid, otherwise the native failure text. */
    fun parse(toml: String): String?
}

object EasyTierNativeConfigParser : NativeConfigParser {
    override fun parse(toml: String): String? = try {
        if (EasyTierJni.parseConfig(toml) == 0) null else EasyTierJni.getLastError() ?: "EasyTier rejected the configuration"
    } catch (error: RuntimeException) {
        EasyTierJni.getLastError() ?: error.message ?: "EasyTier rejected the configuration"
    }
}

class ProfileValidator(private val nativeParser: NativeConfigParser = EasyTierNativeConfigParser) {
    fun validate(profile: EasyTierProfile): Map<String, String> = buildMap {
        if (profile.name.isBlank()) put("name", "Profile name is required")
        if (profile.networkName.isBlank()) put("networkName", "Network name is required")
        validateList("peerUrls", profile.peerUrls)
        validateList("listeners", profile.listeners)
        validateList("manualRoutes", profile.manualRoutes)
        validateList("proxyCidrs", profile.proxyCidrs)
        if (!profile.dhcp && !profile.virtualIpv4.isValidIpv4Cidr()) {
            put("virtualIpv4", "A valid IPv4 CIDR is required when DHCP is off")
        }
        if (profile.mtu !in 576..9000) put("mtu", "MTU must be between 576 and 9000")
        val advancedToml = profile.advancedToml?.takeIf { it.isNotBlank() }
        if (profile.tunMode == TunMode.ROOT_TUN && advancedToml != null) {
            put("advancedToml", "Advanced TOML is supported only by VPN Service")
        } else if (advancedToml != null) {
            nativeParser.parse(advancedToml)?.let { put("advancedToml", it) }
        }
    }

    private fun MutableMap<String, String>.validateList(field: String, values: List<String>) {
        if (values.any { it.isBlank() }) put(field, "Entries cannot be blank")
    }
}

object TomlConfigBuilder {
    fun build(profile: EasyTierProfile): String = profile.advancedToml?.takeIf { it.isNotBlank() } ?: buildStructured(profile)

    fun rootTunSpec(profile: EasyTierProfile): RootTunSpec = RootTunSpec(
        ipv4Cidr = profile.virtualIpv4?.trim(),
        mtu = profile.mtu,
        manualRoutes = profile.manualRoutes.map(String::trim),
        proxyCidrs = profile.proxyCidrs.map(String::trim),
    )

    private fun buildStructured(profile: EasyTierProfile): String = buildString {
        appendTomlString("instance_name", profile.id)
        append("dhcp = ${profile.dhcp}\n")
        profile.virtualIpv4?.trim()?.takeIf { it.isNotEmpty() }?.let { appendTomlString("ipv4", it) }
        appendTomlArray("listeners", profile.listeners)
        appendTomlArray("routes", profile.manualRoutes)
        profile.peerUrls.map(String::trim).filter(String::isNotEmpty).forEach { peer ->
            append("\n[[peer]]\n")
            appendTomlString("uri", peer)
        }
        append("\n[network_identity]\n")
        appendTomlString("network_name", profile.networkName.trim())
        appendTomlString("network_secret", profile.networkSecret)
        profile.proxyCidrs.map(String::trim).filter(String::isNotEmpty).forEach { cidr ->
            append("\n[[proxy_network]]\n")
            appendTomlString("cidr", cidr)
        }
        append("\n[flags]\n")
        appendTomlString("dev_name", "easytier0")
        append("no_tun = false\n")
        append("mtu = ${profile.mtu}\n")
        append("accept_dns = ${profile.enableMagicDns && profile.tunMode == TunMode.VPN_SERVICE}\n")
    }

    private fun StringBuilder.appendTomlArray(key: String, values: List<String>) {
        val trimmed = values.map(String::trim).filter(String::isNotEmpty)
        if (trimmed.isNotEmpty()) append("$key = [${trimmed.joinToString { "\"${it.tomlEscape()}\"" }}]\n")
    }

    private fun StringBuilder.appendTomlString(key: String, value: String) {
        append("$key = \"${value.tomlEscape()}\"\n")
    }

    private fun String.tomlEscape() = replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun String?.isValidIpv4Cidr(): Boolean {
    val value = this?.trim().orEmpty()
    val parts = value.split('/', limit = 2)
    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: return false
    if (parts.size != 2 || prefix !in 0..32) return false
    return runCatching {
        val address = InetAddress.getByName(parts[0])
        address.address.size == 4 && address.hostAddress == parts[0]
    }.getOrDefault(false)
}

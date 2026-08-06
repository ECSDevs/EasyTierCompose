package cc.ptoe.easytier.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.ui.components.ChoiceRow
import cc.ptoe.easytier.compose.ui.components.FormField
import cc.ptoe.easytier.compose.ui.components.ListField
import cc.ptoe.easytier.compose.ui.components.PeerListField
import cc.ptoe.easytier.compose.ui.components.PortForwardListField
import cc.ptoe.easytier.compose.ui.components.ProxyNetworkListField
import cc.ptoe.easytier.compose.ui.components.SectionCard
import cc.ptoe.easytier.compose.ui.components.SwitchRow

@Composable
internal fun ProfileEditorScreen(
    profile: EasyTierProfile,
    errors: Map<String, String>,
    running: Boolean,
    update: ((EasyTierProfile) -> EasyTierProfile) -> Unit,
    save: () -> Unit,
) {
    var revealSecret by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (running) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "Disconnect before saving changes to this profile.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            SectionCard(title = "General", icon = Icons.Default.Info) {
                FormField("Profile name", profile.name, errors["name"]) { v -> update { it.copy(name = v) } }
                FormField("Hostname", profile.hostname.orEmpty(), null) { v -> update { it.copy(hostname = v.ifBlank { null }) } }
                FormField("Network name", profile.networkName, errors["networkName"]) { v -> update { it.copy(networkName = v) } }
                FormField(
                    label = "Network secret",
                    value = profile.networkSecret,
                    error = null,
                    transformation = if (revealSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealSecret = !revealSecret }) {
                            Icon(
                                imageVector = if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealSecret) "Hide secret" else "Reveal secret",
                            )
                        }
                    },
                ) { v -> update { it.copy(networkSecret = v) } }
                SwitchRow("Use DHCP", profile.dhcp) { checked -> update { it.copy(dhcp = checked, virtualIpv4 = if (checked) null else it.virtualIpv4) } }
                if (!profile.dhcp) {
                    FormField("Static IPv4 CIDR", profile.virtualIpv4.orEmpty(), errors["virtualIpv4"]) { v -> update { it.copy(virtualIpv4 = v.ifBlank { null }) } }
                }
                FormField("Static IPv6 CIDR", profile.virtualIpv6.orEmpty(), errors["virtualIpv6"]) { v -> update { it.copy(virtualIpv6 = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = "Network & Peers", icon = Icons.Default.Router) {
                PeerListField(profile.peers, errors["peers"]) { v -> update { it.copy(peers = v) } }
                ListField("Listeners", profile.listeners, errors["listeners"]) { v -> update { it.copy(listeners = v) } }
                ListField("Mapped listeners", profile.mappedListeners, errors["mappedListeners"]) { v -> update { it.copy(mappedListeners = v) } }
            }
        }

        item {
            SectionCard(title = "Routing", icon = Icons.Default.Tune) {
                SwitchRow("Magic DNS", profile.enableMagicDns) { checked -> update { it.copy(enableMagicDns = checked) } }
                FormField("TLD DNS zone", profile.tldDnsZone, errors["tldDnsZone"]) { v -> update { it.copy(tldDnsZone = v) } }
                ProxyNetworkListField(profile.proxyNetworks, errors["proxyNetworks"]) { v -> update { it.copy(proxyNetworks = v) } }
                ListField("Manual routes", profile.manualRoutes, errors["manualRoutes"]) { v -> update { it.copy(manualRoutes = v) } }
                SwitchRow("Enable exit node", profile.enableExitNode) { checked -> update { it.copy(enableExitNode = checked) } }
                ListField("Exit nodes", profile.exitNodes, errors["exitNodes"]) { v -> update { it.copy(exitNodes = v) } }
            }
        }

        item {
            SectionCard(title = "IPv6 Public Address", icon = Icons.Default.Router) {
                SwitchRow("Provider", profile.ipv6PublicAddrProvider) { checked -> update { it.copy(ipv6PublicAddrProvider = checked) } }
                SwitchRow("Auto", profile.ipv6PublicAddrAuto) { checked -> update { it.copy(ipv6PublicAddrAuto = checked) } }
                FormField("IPv6 public prefix (CIDR)", profile.ipv6PublicAddrPrefix.orEmpty(), errors["ipv6PublicAddrPrefix"]) { v -> update { it.copy(ipv6PublicAddrPrefix = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = "Port Forwards", icon = Icons.Default.Tune) {
                PortForwardListField(profile.portForwards, errors["portForwards"]) { v -> update { it.copy(portForwards = v) } }
            }
        }

        item {
            SectionCard(title = "VPN Portal (WireGuard)", icon = Icons.Default.VpnKey) {
                val portal = profile.vpnPortal
                SwitchRow("Enable WireGuard portal", portal != null) { checked ->
                    // Auto-fill safe defaults (random CGNAT /24 + standard WG
                    // listen port) so users never end up with unusable values
                    // like a loopback client CIDR.
                    update { it.copy(vpnPortal = if (checked) VpnPortal.generateDefault() else null) }
                }
                if (portal != null) {
                    FormField("Client CIDR", portal.clientCidr, errors["vpnPortal"]) { v -> update { it.copy(vpnPortal = portal.copy(clientCidr = v)) } }
                    FormField("WireGuard listen", portal.wireguardListen, errors["vpnPortal"]) { v -> update { it.copy(vpnPortal = portal.copy(wireguardListen = v)) } }
                }
            }
        }

        item {
            SectionCard(title = "Secure Mode", icon = Icons.Default.VpnKey) {
                SwitchRow("Enable secure mode", profile.secureMode.enabled) { checked -> update { it.copy(secureMode = it.secureMode.copy(enabled = checked)) } }
                if (profile.secureMode.enabled) {
                    FormField("Local private key", profile.secureMode.localPrivateKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPrivateKey = v.ifBlank { null })) } }
                    FormField("Local public key", profile.secureMode.localPublicKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPublicKey = v.ifBlank { null })) } }
                }
            }
        }

        item {
            SectionCard(title = "STUN & Whitelists", icon = Icons.Default.Router) {
                ListField("STUN servers", profile.stunServers, errors["stunServers"]) { v -> update { it.copy(stunServers = v) } }
                ListField("STUN servers (IPv6)", profile.stunServersV6, errors["stunServersV6"]) { v -> update { it.copy(stunServersV6 = v) } }
                ListField("TCP whitelist", profile.tcpWhitelist, errors["tcpWhitelist"]) { v -> update { it.copy(tcpWhitelist = v) } }
                ListField("UDP whitelist", profile.udpWhitelist, errors["udpWhitelist"]) { v -> update { it.copy(udpWhitelist = v) } }
                FormField("Relay network whitelist", profile.relayNetworkWhitelist, null) { v -> update { it.copy(relayNetworkWhitelist = v) } }
            }
        }

        item {
            SectionCard(title = "Flags — General", icon = Icons.Default.Tune) {
                ChoiceRow("Default protocol", profile.defaultProtocol, listOf("tcp", "udp", "wss")) { v -> update { it.copy(defaultProtocol = v) } }
                SwitchRow("Enable encryption", profile.enableEncryption) { c -> update { it.copy(enableEncryption = c) } }
                ChoiceRow("Encryption algorithm", profile.encryptionAlgorithm.name, EncryptionAlgorithm.entries.map { it.name }) { v ->
                    update { it.copy(encryptionAlgorithm = EncryptionAlgorithm.valueOf(v)) }
                }
                ChoiceRow("Data compression", profile.dataCompressAlgo.name, CompressionAlgo.entries.map { it.name }) { v ->
                    update { it.copy(dataCompressAlgo = CompressionAlgo.valueOf(v)) }
                }
                SwitchRow("Enable IPv6", profile.enableIpv6) { c -> update { it.copy(enableIpv6 = c) } }
                SwitchRow("Latency first", profile.latencyFirst) { c -> update { it.copy(latencyFirst = c) } }
                SwitchRow("Bind device", profile.bindDevice) { c -> update { it.copy(bindDevice = c) } }
                SwitchRow("Private mode", profile.privateMode) { c -> update { it.copy(privateMode = c) } }
                SwitchRow("Relay all peer RPC", profile.relayAllPeerRpc) { c -> update { it.copy(relayAllPeerRpc = c) } }
                SwitchRow("Proxy forward by system", profile.proxyForwardBySystem) { c -> update { it.copy(proxyForwardBySystem = c) } }
                SwitchRow("Disable relay data", profile.disableRelayData) { c -> update { it.copy(disableRelayData = c) } }
                SwitchRow("Enable UDP broadcast relay", profile.enableUdpBroadcastRelay) { c -> update { it.copy(enableUdpBroadcastRelay = c) } }
            }
        }

        item {
            SectionCard(title = "Flags — P2P", icon = Icons.Default.Tune) {
                SwitchRow("Disable P2P", profile.disableP2p) { c -> update { it.copy(disableP2p = c) } }
                SwitchRow("P2P only", profile.p2pOnly) { c -> update { it.copy(p2pOnly = c) } }
                SwitchRow("Lazy P2P", profile.lazyP2p) { c -> update { it.copy(lazyP2p = c) } }
                SwitchRow("Disable TCP hole punching", profile.disableTcpHolePunching) { c -> update { it.copy(disableTcpHolePunching = c) } }
                SwitchRow("Disable UDP hole punching", profile.disableUdpHolePunching) { c -> update { it.copy(disableUdpHolePunching = c) } }
                SwitchRow("Disable symmetric hole punching", profile.disableSymHolePunching) { c -> update { it.copy(disableSymHolePunching = c) } }
                SwitchRow("Disable UPnP", profile.disableUpnp) { c -> update { it.copy(disableUpnp = c) } }
            }
        }

        item {
            SectionCard(title = "Flags — KCP Proxy", icon = Icons.Default.Tune) {
                SwitchRow("Enable KCP proxy", profile.enableKcpProxy) { c -> update { it.copy(enableKcpProxy = c) } }
                SwitchRow("Disable KCP input", profile.disableKcpInput) { c -> update { it.copy(disableKcpInput = c) } }
                SwitchRow("Disable relay KCP", profile.disableRelayKcp) { c -> update { it.copy(disableRelayKcp = c) } }
                SwitchRow("Enable relay foreign network KCP", profile.enableRelayForeignNetworkKcp) { c -> update { it.copy(enableRelayForeignNetworkKcp = c) } }
            }
        }

        item {
            SectionCard(title = "Flags — QUIC Proxy", icon = Icons.Default.Tune) {
                SwitchRow("Enable QUIC proxy", profile.enableQuicProxy) { c -> update { it.copy(enableQuicProxy = c) } }
                SwitchRow("Disable QUIC input", profile.disableQuicInput) { c -> update { it.copy(disableQuicInput = c) } }
                SwitchRow("Disable relay QUIC", profile.disableRelayQuic) { c -> update { it.copy(disableRelayQuic = c) } }
                SwitchRow("Enable relay foreign network QUIC", profile.enableRelayForeignNetworkQuic) { c -> update { it.copy(enableRelayForeignNetworkQuic = c) } }
            }
        }

        item {
            errors["form"]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

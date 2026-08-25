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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.ValidationMessage
import cc.ptoe.easytier.compose.core.resolve
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.ui.components.ChoiceOption
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
    errors: Map<String, ValidationMessage>,
    running: Boolean,
    update: ((EasyTierProfile) -> EasyTierProfile) -> Unit,
    save: () -> Unit,
) {
    val context = LocalContext.current
    fun error(key: String): String? = errors[key]?.resolve(context)
    var revealSecret by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (running) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.editor_disconnect_before_save),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_general), icon = Icons.Default.Info) {
                FormField(stringResource(R.string.editor_profile_name), profile.name, error("name")) { v -> update { it.copy(name = v) } }
                FormField(stringResource(R.string.editor_hostname), profile.hostname.orEmpty(), null) { v -> update { it.copy(hostname = v.ifBlank { null }) } }
                FormField(stringResource(R.string.editor_network_name), profile.networkName, error("networkName")) { v -> update { it.copy(networkName = v) } }
                FormField(
                    label = stringResource(R.string.editor_network_secret),
                    value = profile.networkSecret,
                    error = null,
                    transformation = if (revealSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealSecret = !revealSecret }) {
                            Icon(
                                imageVector = if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(if (revealSecret) R.string.content_hide_secret else R.string.content_reveal_secret),
                            )
                        }
                    },
                ) { v -> update { it.copy(networkSecret = v) } }
                SwitchRow(stringResource(R.string.editor_use_dhcp), profile.dhcp) { checked -> update { it.copy(dhcp = checked, virtualIpv4 = if (checked) null else it.virtualIpv4) } }
                if (!profile.dhcp) {
                    FormField(stringResource(R.string.editor_static_ipv4_cidr), profile.virtualIpv4.orEmpty(), error("virtualIpv4")) { v -> update { it.copy(virtualIpv4 = v.ifBlank { null }) } }
                }
                FormField(stringResource(R.string.editor_static_ipv6_cidr), profile.virtualIpv6.orEmpty(), error("virtualIpv6")) { v -> update { it.copy(virtualIpv6 = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_network_peers), icon = Icons.Default.Router) {
                PeerListField(profile.peers, error("peers")) { v -> update { it.copy(peers = v) } }
                ListField(stringResource(R.string.editor_listeners), profile.listeners, error("listeners")) { v -> update { it.copy(listeners = v) } }
                ListField(stringResource(R.string.editor_mapped_listeners), profile.mappedListeners, error("mappedListeners")) { v -> update { it.copy(mappedListeners = v) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_routing), icon = Icons.Default.Tune) {
                SwitchRow(stringResource(R.string.editor_magic_dns), profile.enableMagicDns) { checked -> update { it.copy(enableMagicDns = checked) } }
                FormField(stringResource(R.string.editor_tld_dns_zone), profile.tldDnsZone, error("tldDnsZone")) { v -> update { it.copy(tldDnsZone = v) } }
                ProxyNetworkListField(profile.proxyNetworks, error("proxyNetworks")) { v -> update { it.copy(proxyNetworks = v) } }
                ListField(stringResource(R.string.editor_manual_routes), profile.manualRoutes, error("manualRoutes")) { v -> update { it.copy(manualRoutes = v) } }
                SwitchRow(stringResource(R.string.editor_enable_exit_node), profile.enableExitNode) { checked -> update { it.copy(enableExitNode = checked) } }
                ListField(stringResource(R.string.editor_exit_nodes), profile.exitNodes, error("exitNodes")) { v -> update { it.copy(exitNodes = v) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_ipv6_public_address), icon = Icons.Default.Router) {
                SwitchRow(stringResource(R.string.editor_provider), profile.ipv6PublicAddrProvider) { checked -> update { it.copy(ipv6PublicAddrProvider = checked) } }
                SwitchRow(stringResource(R.string.editor_auto), profile.ipv6PublicAddrAuto) { checked -> update { it.copy(ipv6PublicAddrAuto = checked) } }
                FormField(stringResource(R.string.editor_ipv6_public_prefix), profile.ipv6PublicAddrPrefix.orEmpty(), error("ipv6PublicAddrPrefix")) { v -> update { it.copy(ipv6PublicAddrPrefix = v.ifBlank { null }) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_port_forwards), icon = Icons.Default.Tune) {
                PortForwardListField(profile.portForwards, error("portForwards")) { v -> update { it.copy(portForwards = v) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_socks5_proxy), icon = Icons.Default.Tune) {
                FormField(
                    label = stringResource(R.string.editor_socks5_proxy_url),
                    value = profile.socks5Proxy.orEmpty(),
                    error = error("socks5Proxy"),
                ) { v -> update { it.copy(socks5Proxy = v.ifBlank { null }) } }
                Text(
                    stringResource(R.string.editor_socks5_proxy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_vpn_portal), icon = Icons.Default.VpnKey) {
                val portal = profile.vpnPortal
                SwitchRow(stringResource(R.string.editor_enable_wireguard_portal), portal != null) { checked ->
                    // Auto-fill safe defaults so users never end up with unusable portal values.
                    update { it.copy(vpnPortal = if (checked) VpnPortal.generateDefault() else null) }
                }
                if (portal != null) {
                    FormField(stringResource(R.string.editor_client_cidr), portal.clientCidr, error("vpnPortal")) { v -> update { it.copy(vpnPortal = portal.copy(clientCidr = v)) } }
                    FormField(stringResource(R.string.editor_wireguard_listen), portal.wireguardListen, error("vpnPortal")) { v -> update { it.copy(vpnPortal = portal.copy(wireguardListen = v)) } }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_secure_mode), icon = Icons.Default.VpnKey) {
                SwitchRow(stringResource(R.string.editor_enable_secure_mode), profile.secureMode.enabled) { checked -> update { it.copy(secureMode = it.secureMode.copy(enabled = checked)) } }
                if (profile.secureMode.enabled) {
                    FormField(stringResource(R.string.editor_local_private_key), profile.secureMode.localPrivateKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPrivateKey = v.ifBlank { null })) } }
                    FormField(stringResource(R.string.editor_local_public_key), profile.secureMode.localPublicKey.orEmpty(), null) { v -> update { it.copy(secureMode = it.secureMode.copy(localPublicKey = v.ifBlank { null })) } }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_stun_whitelists), icon = Icons.Default.Router) {
                ListField(stringResource(R.string.editor_stun_servers), profile.stunServers, error("stunServers")) { v -> update { it.copy(stunServers = v) } }
                ListField(stringResource(R.string.editor_stun_servers_ipv6), profile.stunServersV6, error("stunServersV6")) { v -> update { it.copy(stunServersV6 = v) } }
                ListField(stringResource(R.string.editor_tcp_whitelist), profile.tcpWhitelist, error("tcpWhitelist")) { v -> update { it.copy(tcpWhitelist = v) } }
                ListField(stringResource(R.string.editor_udp_whitelist), profile.udpWhitelist, error("udpWhitelist")) { v -> update { it.copy(udpWhitelist = v) } }
                FormField(stringResource(R.string.editor_relay_network_whitelist), profile.relayNetworkWhitelist, null) { v -> update { it.copy(relayNetworkWhitelist = v) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_flags_general), icon = Icons.Default.Tune) {
                ChoiceRow(
                    stringResource(R.string.editor_default_protocol),
                    profile.defaultProtocol,
                    listOf(
                        ChoiceOption("tcp", stringResource(R.string.protocol_tcp)),
                        ChoiceOption("udp", stringResource(R.string.protocol_udp)),
                        ChoiceOption("wss", stringResource(R.string.protocol_wss)),
                    ),
                ) { v -> update { it.copy(defaultProtocol = v) } }
                SwitchRow(stringResource(R.string.editor_enable_encryption), profile.enableEncryption) { c -> update { it.copy(enableEncryption = c) } }
                ChoiceRow(
                    stringResource(R.string.editor_encryption_algorithm),
                    profile.encryptionAlgorithm.name,
                    listOf(
                        ChoiceOption(EncryptionAlgorithm.Xor.name, stringResource(R.string.choice_encryption_xor)),
                        ChoiceOption(EncryptionAlgorithm.AesGcm.name, stringResource(R.string.choice_encryption_aes_gcm)),
                        ChoiceOption(EncryptionAlgorithm.Aes256Gcm.name, stringResource(R.string.choice_encryption_aes_256_gcm)),
                        ChoiceOption(EncryptionAlgorithm.ChaCha20.name, stringResource(R.string.choice_encryption_chacha20)),
                    ),
                ) { v -> update { it.copy(encryptionAlgorithm = EncryptionAlgorithm.valueOf(v)) } }
                ChoiceRow(
                    stringResource(R.string.editor_data_compression),
                    profile.dataCompressAlgo.name,
                    listOf(
                        ChoiceOption(CompressionAlgo.None.name, stringResource(R.string.choice_compression_none)),
                        ChoiceOption(CompressionAlgo.Zstd.name, stringResource(R.string.choice_compression_zstd)),
                    ),
                ) { v -> update { it.copy(dataCompressAlgo = CompressionAlgo.valueOf(v)) } }
                SwitchRow(stringResource(R.string.editor_enable_ipv6), profile.enableIpv6) { c -> update { it.copy(enableIpv6 = c) } }
                SwitchRow(stringResource(R.string.editor_latency_first), profile.latencyFirst) { c -> update { it.copy(latencyFirst = c) } }
                SwitchRow(stringResource(R.string.editor_bind_device), profile.bindDevice) { c -> update { it.copy(bindDevice = c) } }
                SwitchRow(stringResource(R.string.editor_private_mode), profile.privateMode) { c -> update { it.copy(privateMode = c) } }
                SwitchRow(stringResource(R.string.editor_relay_all_peer_rpc), profile.relayAllPeerRpc) { c -> update { it.copy(relayAllPeerRpc = c) } }
                SwitchRow(stringResource(R.string.editor_proxy_forward_system), profile.proxyForwardBySystem) { c -> update { it.copy(proxyForwardBySystem = c) } }
                SwitchRow(stringResource(R.string.editor_disable_relay_data), profile.disableRelayData) { c -> update { it.copy(disableRelayData = c) } }
                SwitchRow(stringResource(R.string.editor_enable_udp_broadcast_relay), profile.enableUdpBroadcastRelay) { c -> update { it.copy(enableUdpBroadcastRelay = c) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_flags_p2p), icon = Icons.Default.Tune) {
                SwitchRow(stringResource(R.string.editor_disable_p2p), profile.disableP2p) { c -> update { it.copy(disableP2p = c) } }
                SwitchRow(stringResource(R.string.editor_p2p_only), profile.p2pOnly) { c -> update { it.copy(p2pOnly = c) } }
                SwitchRow(stringResource(R.string.editor_lazy_p2p), profile.lazyP2p) { c -> update { it.copy(lazyP2p = c) } }
                SwitchRow(stringResource(R.string.editor_need_p2p), profile.needP2p) { c -> update { it.copy(needP2p = c) } }
                SwitchRow(stringResource(R.string.editor_disable_tcp_hole_punching), profile.disableTcpHolePunching) { c -> update { it.copy(disableTcpHolePunching = c) } }
                SwitchRow(stringResource(R.string.editor_disable_udp_hole_punching), profile.disableUdpHolePunching) { c -> update { it.copy(disableUdpHolePunching = c) } }
                SwitchRow(stringResource(R.string.editor_disable_symmetric_hole_punching), profile.disableSymHolePunching) { c -> update { it.copy(disableSymHolePunching = c) } }
                SwitchRow(stringResource(R.string.editor_disable_upnp), profile.disableUpnp) { c -> update { it.copy(disableUpnp = c) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_flags_kcp_proxy), icon = Icons.Default.Tune) {
                SwitchRow(stringResource(R.string.editor_enable_kcp_proxy), profile.enableKcpProxy) { c -> update { it.copy(enableKcpProxy = c) } }
                SwitchRow(stringResource(R.string.editor_disable_kcp_input), profile.disableKcpInput) { c -> update { it.copy(disableKcpInput = c) } }
                SwitchRow(stringResource(R.string.editor_disable_relay_kcp), profile.disableRelayKcp) { c -> update { it.copy(disableRelayKcp = c) } }
                SwitchRow(stringResource(R.string.editor_enable_relay_foreign_kcp), profile.enableRelayForeignNetworkKcp) { c -> update { it.copy(enableRelayForeignNetworkKcp = c) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.editor_flags_quic_proxy), icon = Icons.Default.Tune) {
                SwitchRow(stringResource(R.string.editor_enable_quic_proxy), profile.enableQuicProxy) { c -> update { it.copy(enableQuicProxy = c) } }
                SwitchRow(stringResource(R.string.editor_disable_quic_input), profile.disableQuicInput) { c -> update { it.copy(disableQuicInput = c) } }
                SwitchRow(stringResource(R.string.editor_disable_relay_quic), profile.disableRelayQuic) { c -> update { it.copy(disableRelayQuic = c) } }
                SwitchRow(stringResource(R.string.editor_enable_relay_foreign_quic), profile.enableRelayForeignNetworkQuic) { c -> update { it.copy(enableRelayForeignNetworkQuic = c) } }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.settings_device), icon = Icons.Default.Tune) {
                Text(
                    stringResource(R.string.settings_engine_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var tunDeviceName by remember(profile.tunDeviceName) { mutableStateOf(profile.tunDeviceName) }
                var mtuInput by remember(profile.mtu) { mutableStateOf(profile.mtu.toString()) }
                var threadCountInput by remember(profile.multiThreadCount) { mutableStateOf(profile.multiThreadCount.toString()) }
                var foreignRelayBpsInput by remember(profile.foreignRelayBpsLimit) {
                    mutableStateOf(if (profile.foreignRelayBpsLimit >= Long.MAX_VALUE) "" else profile.foreignRelayBpsLimit.toString())
                }
                var instanceRecvBpsInput by remember(profile.instanceRecvBpsLimit) {
                    mutableStateOf(if (profile.instanceRecvBpsLimit >= Long.MAX_VALUE) "" else profile.instanceRecvBpsLimit.toString())
                }
                var socketMarkInput by remember(profile.socketMark) { mutableStateOf(profile.socketMark?.toString() ?: "") }
                fun parseBpsLimit(raw: String): Long? {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) return Long.MAX_VALUE
                    return trimmed.toLongOrNull()?.takeIf { it >= 0 }
                }
                val mtuValid = mtuInput.toIntOrNull() in 576..9000
                val threadCountValid = (threadCountInput.toIntOrNull() ?: 0) > 0
                val foreignRelayBps = parseBpsLimit(foreignRelayBpsInput)
                val instanceRecvBps = parseBpsLimit(instanceRecvBpsInput)
                val socketMarkValid = socketMarkInput.isBlank() ||
                    socketMarkInput.trim().toLongOrNull()?.let { it in 0..0xFFFFFFFFL } == true

                FormField(stringResource(R.string.settings_tun_device_name), tunDeviceName, null) { v ->
                    tunDeviceName = v
                    update { it.copy(tunDeviceName = v) }
                }
                FormField(
                    stringResource(R.string.settings_mtu),
                    mtuInput,
                    if (mtuValid) null else stringResource(R.string.error_mtu_range),
                ) { v ->
                    mtuInput = v.filter { c -> c.isDigit() }.take(5)
                    mtuInput.toIntOrNull()?.takeIf { it in 576..9000 }?.let { mtu ->
                        update { it.copy(mtu = mtu) }
                    }
                }
                SwitchRow(stringResource(R.string.settings_multi_thread), profile.multiThread) { c -> update { it.copy(multiThread = c) } }
                FormField(
                    stringResource(R.string.settings_multi_thread_count),
                    threadCountInput,
                    if (threadCountValid) null else stringResource(R.string.error_thread_count_positive),
                ) { v ->
                    threadCountInput = v.filter { c -> c.isDigit() }.take(3)
                    threadCountInput.toIntOrNull()?.takeIf { it > 0 }?.let { count ->
                        update { it.copy(multiThreadCount = count) }
                    }
                }
                FormField(
                    stringResource(R.string.settings_foreign_relay_bps_limit),
                    foreignRelayBpsInput,
                    if (foreignRelayBps != null) null else stringResource(R.string.settings_bps_invalid),
                ) { v ->
                    foreignRelayBpsInput = v.filter { c -> c.isDigit() }.take(19)
                    parseBpsLimit(foreignRelayBpsInput)?.let { limit ->
                        update { it.copy(foreignRelayBpsLimit = limit) }
                    }
                }
                FormField(
                    stringResource(R.string.settings_instance_recv_bps_limit),
                    instanceRecvBpsInput,
                    if (instanceRecvBps != null) null else stringResource(R.string.settings_bps_invalid),
                ) { v ->
                    instanceRecvBpsInput = v.filter { c -> c.isDigit() }.take(19)
                    parseBpsLimit(instanceRecvBpsInput)?.let { limit ->
                        update { it.copy(instanceRecvBpsLimit = limit) }
                    }
                }
                FormField(
                    stringResource(R.string.settings_socket_mark),
                    socketMarkInput,
                    if (socketMarkValid) null else stringResource(R.string.settings_socket_mark_invalid),
                ) { v ->
                    socketMarkInput = v.filter { c -> c.isDigit() }.take(10)
                    if (socketMarkInput.isBlank()) {
                        update { it.copy(socketMark = null) }
                    } else {
                        socketMarkInput.trim().toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }?.let { mark ->
                            update { it.copy(socketMark = mark) }
                        }
                    }
                }
                Text(
                    stringResource(R.string.settings_socket_mark_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            errors["form"]?.let { message ->
                Text(message.resolve(context), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

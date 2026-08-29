package cc.ptoe.easytier.compose.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.ui.EasyTierUiState
import cc.ptoe.easytier.compose.ui.components.ChoiceOption
import cc.ptoe.easytier.compose.ui.components.ChoiceRow
import cc.ptoe.easytier.compose.ui.components.FormField
import cc.ptoe.easytier.compose.ui.components.ListField
import cc.ptoe.easytier.compose.ui.components.PeerListField
import cc.ptoe.easytier.compose.ui.components.PortForwardListField
import cc.ptoe.easytier.compose.ui.components.ProxyNetworkListField
import cc.ptoe.easytier.compose.ui.components.SettingsGroup
import cc.ptoe.easytier.compose.ui.components.SwitchRow
import cc.ptoe.easytier.compose.ui.components.VpnPortalClientListField

/**
 * Global overrides editor. Mirrors the profile editor's sections so every
 * profile option can be overridden across all profiles (merge-and-override).
 * A non-null value in [GlobalSettings] wins over the profile value.
 */
@Composable
internal fun GlobalSettingsScreen(
    state: EasyTierUiState,
    onGlobalSettings: (GlobalSettings) -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId }
        ?: EasyTierProfile(
            id = "", name = "", networkName = "", networkSecret = "",
            virtualIpv4 = null, dhcp = false, enableMagicDns = false, tunMode = TunMode.VPN_SERVICE,
        )
    val settings = state.globalSettings

    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_general))
                    OverrideStringField(stringResource(R.string.editor_hostname), profile.hostname, settings.hostname) { onGlobalSettings(settings.copy(hostname = it)) }
                    OverrideStringField(stringResource(R.string.editor_network_name), profile.networkName, settings.networkName) { onGlobalSettings(settings.copy(networkName = it)) }
                    // The network secret is masked: the reference never reveals its value,
                    // and the override editor uses password input.
                    val secretOverride = settings.networkSecret
                    OverrideItem(
                        label = stringResource(R.string.editor_network_secret),
                        profileSummary = profileSummary(
                            if (profile.networkSecret.isNullOrBlank()) stringResource(R.string.global_value_not_set) else stringResource(R.string.global_value_set),
                        ),
                        overridden = secretOverride != null,
                        onOverrideChange = { on -> onGlobalSettings(settings.copy(networkSecret = if (on) profile.networkSecret ?: "" else null)) },
                    ) {
                        if (secretOverride != null) {
                            var secretInput by remember(secretOverride) { mutableStateOf(secretOverride) }
                            FormField(
                                stringResource(R.string.editor_network_secret),
                                secretInput,
                                null,
                                transformation = PasswordVisualTransformation(),
                            ) { v ->
                                secretInput = v
                                onGlobalSettings(settings.copy(networkSecret = v.ifBlank { null }))
                            }
                        }
                    }
                    OverrideBoolField(stringResource(R.string.editor_use_dhcp), profile.dhcp, settings.dhcp) { onGlobalSettings(settings.copy(dhcp = it)) }
                    OverrideStringField(stringResource(R.string.editor_static_ipv4_cidr), profile.virtualIpv4, settings.virtualIpv4) { onGlobalSettings(settings.copy(virtualIpv4 = it)) }
                    OverrideStringField(stringResource(R.string.editor_static_ipv6_cidr), profile.virtualIpv6, settings.virtualIpv6) { onGlobalSettings(settings.copy(virtualIpv6 = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_network_peers))
                    OverrideItem(
                        label = stringResource(R.string.list_peers),
                        profileSummary = profileSummaryCount(profile.peers.size),
                        overridden = settings.peers != null,
                        onOverrideChange = { on -> onGlobalSettings(settings.copy(peers = if (on) profile.peers else null)) },
                    ) {
                        PeerListField(settings.peers ?: profile.peers, null) { onGlobalSettings(settings.copy(peers = it)) }
                    }
                    OverrideListField(stringResource(R.string.editor_listeners), profile.listeners, settings.listeners) { onGlobalSettings(settings.copy(listeners = it)) }
                    OverrideListField(stringResource(R.string.editor_mapped_listeners), profile.mappedListeners, settings.mappedListeners) { onGlobalSettings(settings.copy(mappedListeners = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_routing))
                    OverrideBoolField(stringResource(R.string.editor_magic_dns), profile.enableMagicDns, settings.enableMagicDns) { onGlobalSettings(settings.copy(enableMagicDns = it)) }
                    OverrideStringField(stringResource(R.string.editor_tld_dns_zone), profile.tldDnsZone, settings.tldDnsZone) { onGlobalSettings(settings.copy(tldDnsZone = it)) }
                    OverrideItem(
                        label = stringResource(R.string.list_proxy_networks),
                        profileSummary = profileSummaryCount(profile.proxyNetworks.size),
                        overridden = settings.proxyNetworks != null,
                        onOverrideChange = { on -> onGlobalSettings(settings.copy(proxyNetworks = if (on) profile.proxyNetworks else null)) },
                    ) {
                        ProxyNetworkListField(settings.proxyNetworks ?: profile.proxyNetworks, null) { onGlobalSettings(settings.copy(proxyNetworks = it)) }
                    }
                    OverrideListField(stringResource(R.string.editor_manual_routes), profile.manualRoutes, settings.manualRoutes) { onGlobalSettings(settings.copy(manualRoutes = it)) }
                    OverrideBoolField(stringResource(R.string.editor_enable_exit_node), profile.enableExitNode, settings.enableExitNode) { onGlobalSettings(settings.copy(enableExitNode = it)) }
                    OverrideListField(stringResource(R.string.editor_exit_nodes), profile.exitNodes, settings.exitNodes) { onGlobalSettings(settings.copy(exitNodes = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_ipv6_public_address))
                    OverrideBoolField(stringResource(R.string.editor_provider), profile.ipv6PublicAddrProvider, settings.ipv6PublicAddrProvider) { onGlobalSettings(settings.copy(ipv6PublicAddrProvider = it)) }
                    OverrideBoolField(stringResource(R.string.editor_auto), profile.ipv6PublicAddrAuto, settings.ipv6PublicAddrAuto) { onGlobalSettings(settings.copy(ipv6PublicAddrAuto = it)) }
                    OverrideStringField(stringResource(R.string.editor_ipv6_public_prefix), profile.ipv6PublicAddrPrefix, settings.ipv6PublicAddrPrefix) { onGlobalSettings(settings.copy(ipv6PublicAddrPrefix = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_port_forwards))
                    OverrideItem(
                        label = stringResource(R.string.list_port_forwards),
                        profileSummary = profileSummaryCount(profile.portForwards.size),
                        overridden = settings.portForwards != null,
                        onOverrideChange = { on -> onGlobalSettings(settings.copy(portForwards = if (on) profile.portForwards else null)) },
                    ) {
                        PortForwardListField(settings.portForwards ?: profile.portForwards, null) { onGlobalSettings(settings.copy(portForwards = it)) }
                    }
                    OverrideStringField(stringResource(R.string.editor_socks5_proxy_url), profile.socks5Proxy, settings.socks5Proxy) { onGlobalSettings(settings.copy(socks5Proxy = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_vpn_portal))
                    val portal = settings.vpnPortal
                    OverrideItem(
                        label = stringResource(R.string.editor_vpn_portal),
                        profileSummary = profile.vpnPortal?.let { profileSummary(it.wireguardListen) }
                            ?: profileSummary(stringResource(R.string.global_value_not_set)),
                        overridden = portal != null,
                        onOverrideChange = { on ->
                            onGlobalSettings(settings.copy(vpnPortal = if (on) profile.vpnPortal ?: VpnPortal.generateDefault() else null))
                        },
                    ) {
                        if (portal != null) {
                            FormField(stringResource(R.string.editor_wireguard_listen), portal.wireguardListen, null) { v -> onGlobalSettings(settings.copy(vpnPortal = portal.copy(wireguardListen = v))) }
                            FormField(
                                stringResource(R.string.editor_wireguard_private_key),
                                portal.wireguardPrivateKey.orEmpty(),
                                null
                            ) { v ->
                                onGlobalSettings(
                                    settings.copy(
                                        vpnPortal = portal.copy(
                                            wireguardPrivateKey = v.ifBlank { null })
                                    )
                                )
                            }
                            VpnPortalClientListField(
                                portal.clients,
                                null
                            ) { clients ->
                                onGlobalSettings(
                                    settings.copy(
                                        vpnPortal = portal.copy(
                                            clients = clients
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_secure_mode))
                    val secureMode = settings.secureMode
                    OverrideItem(
                        label = stringResource(R.string.editor_secure_mode),
                        profileSummary = profileSummaryBool(profile.secureMode.enabled),
                        overridden = secureMode != null,
                        onOverrideChange = { on -> onGlobalSettings(settings.copy(secureMode = if (on) profile.secureMode else null)) },
                    ) {
                        if (secureMode != null) {
                            SwitchRow(stringResource(R.string.editor_enable_secure_mode), secureMode.enabled) { checked -> onGlobalSettings(settings.copy(secureMode = secureMode.copy(enabled = checked))) }
                            FormField(stringResource(R.string.editor_local_private_key), secureMode.localPrivateKey.orEmpty(), null) { v -> onGlobalSettings(settings.copy(secureMode = secureMode.copy(localPrivateKey = v.ifBlank { null }))) }
                            FormField(stringResource(R.string.editor_local_public_key), secureMode.localPublicKey.orEmpty(), null) { v -> onGlobalSettings(settings.copy(secureMode = secureMode.copy(localPublicKey = v.ifBlank { null }))) }
                        }
                    }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_stun_whitelists))
                    OverrideListField(stringResource(R.string.editor_stun_servers), profile.stunServers, settings.stunServers) { onGlobalSettings(settings.copy(stunServers = it)) }
                    OverrideListField(
                        stringResource(R.string.editor_stun_servers_tcp),
                        profile.tcpStunServers,
                        settings.tcpStunServers
                    ) { onGlobalSettings(settings.copy(tcpStunServers = it)) }
                    OverrideListField(stringResource(R.string.editor_stun_servers_ipv6), profile.stunServersV6, settings.stunServersV6) { onGlobalSettings(settings.copy(stunServersV6 = it)) }
                    OverrideListField(stringResource(R.string.editor_tcp_whitelist), profile.tcpWhitelist, settings.tcpWhitelist) { onGlobalSettings(settings.copy(tcpWhitelist = it)) }
                    OverrideListField(stringResource(R.string.editor_udp_whitelist), profile.udpWhitelist, settings.udpWhitelist) { onGlobalSettings(settings.copy(udpWhitelist = it)) }
                    OverrideStringField(stringResource(R.string.editor_relay_network_whitelist), profile.relayNetworkWhitelist, settings.relayNetworkWhitelist) { onGlobalSettings(settings.copy(relayNetworkWhitelist = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_flags_general))
                    OverrideChoiceField(
                        stringResource(R.string.editor_default_protocol),
                        profile.defaultProtocol,
                        settings.defaultProtocol,
                        listOf(
                            ChoiceOption("tcp", stringResource(R.string.protocol_tcp)),
                            ChoiceOption("udp", stringResource(R.string.protocol_udp)),
                            ChoiceOption("wss", stringResource(R.string.protocol_wss)),
                        ),
                    ) { onGlobalSettings(settings.copy(defaultProtocol = it)) }
                    OverrideBoolField(stringResource(R.string.editor_enable_encryption), profile.enableEncryption, settings.enableEncryption) { onGlobalSettings(settings.copy(enableEncryption = it)) }
                    OverrideChoiceField(
                        stringResource(R.string.editor_encryption_algorithm),
                        profile.encryptionAlgorithm.name,
                        settings.encryptionAlgorithm?.name,
                        listOf(
                            ChoiceOption(EncryptionAlgorithm.Xor.name, stringResource(R.string.choice_encryption_xor)),
                            ChoiceOption(EncryptionAlgorithm.AesGcm.name, stringResource(R.string.choice_encryption_aes_gcm)),
                            ChoiceOption(EncryptionAlgorithm.Aes256Gcm.name, stringResource(R.string.choice_encryption_aes_256_gcm)),
                            ChoiceOption(EncryptionAlgorithm.ChaCha20.name, stringResource(R.string.choice_encryption_chacha20)),
                        ),
                    ) { onGlobalSettings(settings.copy(encryptionAlgorithm = it?.let(EncryptionAlgorithm::valueOf))) }
                    OverrideChoiceField(
                        stringResource(R.string.editor_data_compression),
                        profile.dataCompressAlgo.name,
                        settings.dataCompressAlgo?.name,
                        listOf(
                            ChoiceOption(CompressionAlgo.None.name, stringResource(R.string.choice_compression_none)),
                            ChoiceOption(CompressionAlgo.Zstd.name, stringResource(R.string.choice_compression_zstd)),
                        ),
                    ) { onGlobalSettings(settings.copy(dataCompressAlgo = it?.let(CompressionAlgo::valueOf))) }
                    OverrideBoolField(stringResource(R.string.editor_enable_ipv6), profile.enableIpv6, settings.enableIpv6) { onGlobalSettings(settings.copy(enableIpv6 = it)) }
                    OverrideBoolField(stringResource(R.string.editor_latency_first), profile.latencyFirst, settings.latencyFirst) { onGlobalSettings(settings.copy(latencyFirst = it)) }
                    OverrideBoolField(stringResource(R.string.editor_bind_device), profile.bindDevice, settings.bindDevice) { onGlobalSettings(settings.copy(bindDevice = it)) }
                    OverrideBoolField(stringResource(R.string.editor_private_mode), profile.privateMode, settings.privateMode) { onGlobalSettings(settings.copy(privateMode = it)) }
                    OverrideBoolField(stringResource(R.string.editor_relay_all_peer_rpc), profile.relayAllPeerRpc, settings.relayAllPeerRpc) { onGlobalSettings(settings.copy(relayAllPeerRpc = it)) }
                    OverrideBoolField(stringResource(R.string.editor_proxy_forward_system), profile.proxyForwardBySystem, settings.proxyForwardBySystem) { onGlobalSettings(settings.copy(proxyForwardBySystem = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_relay_data), profile.disableRelayData, settings.disableRelayData) { onGlobalSettings(settings.copy(disableRelayData = it)) }
                    OverrideBoolField(stringResource(R.string.editor_enable_udp_broadcast_relay), profile.enableUdpBroadcastRelay, settings.enableUdpBroadcastRelay) { onGlobalSettings(settings.copy(enableUdpBroadcastRelay = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_flags_p2p))
                    OverrideBoolField(stringResource(R.string.editor_disable_p2p), profile.disableP2p, settings.disableP2p) { onGlobalSettings(settings.copy(disableP2p = it)) }
                    OverrideBoolField(stringResource(R.string.editor_p2p_only), profile.p2pOnly, settings.p2pOnly) { onGlobalSettings(settings.copy(p2pOnly = it)) }
                    OverrideBoolField(stringResource(R.string.editor_lazy_p2p), profile.lazyP2p, settings.lazyP2p) { onGlobalSettings(settings.copy(lazyP2p = it)) }
                    OverrideBoolField(stringResource(R.string.editor_need_p2p), profile.needP2p, settings.needP2p) { onGlobalSettings(settings.copy(needP2p = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_tcp_hole_punching), profile.disableTcpHolePunching, settings.disableTcpHolePunching) { onGlobalSettings(settings.copy(disableTcpHolePunching = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_udp_hole_punching), profile.disableUdpHolePunching, settings.disableUdpHolePunching) { onGlobalSettings(settings.copy(disableUdpHolePunching = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_symmetric_hole_punching), profile.disableSymHolePunching, settings.disableSymHolePunching) { onGlobalSettings(settings.copy(disableSymHolePunching = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_upnp), profile.disableUpnp, settings.disableUpnp) { onGlobalSettings(settings.copy(disableUpnp = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_flags_kcp_proxy))
                    OverrideBoolField(stringResource(R.string.editor_enable_kcp_proxy), profile.enableKcpProxy, settings.enableKcpProxy) { onGlobalSettings(settings.copy(enableKcpProxy = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_kcp_input), profile.disableKcpInput, settings.disableKcpInput) { onGlobalSettings(settings.copy(disableKcpInput = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_relay_kcp), profile.disableRelayKcp, settings.disableRelayKcp) { onGlobalSettings(settings.copy(disableRelayKcp = it)) }
                    OverrideBoolField(stringResource(R.string.editor_enable_relay_foreign_kcp), profile.enableRelayForeignNetworkKcp, settings.enableRelayForeignNetworkKcp) { onGlobalSettings(settings.copy(enableRelayForeignNetworkKcp = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.editor_flags_quic_proxy))
                    OverrideBoolField(stringResource(R.string.editor_enable_quic_proxy), profile.enableQuicProxy, settings.enableQuicProxy) { onGlobalSettings(settings.copy(enableQuicProxy = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_quic_input), profile.disableQuicInput, settings.disableQuicInput) { onGlobalSettings(settings.copy(disableQuicInput = it)) }
                    OverrideBoolField(stringResource(R.string.editor_disable_relay_quic), profile.disableRelayQuic, settings.disableRelayQuic) { onGlobalSettings(settings.copy(disableRelayQuic = it)) }
                    OverrideBoolField(stringResource(R.string.editor_enable_relay_foreign_quic), profile.enableRelayForeignNetworkQuic, settings.enableRelayForeignNetworkQuic) { onGlobalSettings(settings.copy(enableRelayForeignNetworkQuic = it)) }
                }
            }
        }

        item {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.settings_device))
                    OverrideStringField(stringResource(R.string.settings_tun_device_name), profile.tunDeviceName, settings.tunDeviceName) { onGlobalSettings(settings.copy(tunDeviceName = it)) }
                    OverrideIntField(
                        stringResource(R.string.settings_mtu),
                        profile.mtu,
                        settings.mtu,
                        digits = 5,
                        valid = { it in 576..9000 },
                        error = stringResource(R.string.error_mtu_range),
                    ) { onGlobalSettings(settings.copy(mtu = it)) }
                    OverrideBoolField(stringResource(R.string.settings_multi_thread), profile.multiThread, settings.multiThread) { onGlobalSettings(settings.copy(multiThread = it)) }
                    OverrideIntField(
                        stringResource(R.string.settings_multi_thread_count),
                        profile.multiThreadCount,
                        settings.multiThreadCount,
                        digits = 3,
                        valid = { it > 0 },
                        error = stringResource(R.string.error_thread_count_positive),
                    ) { onGlobalSettings(settings.copy(multiThreadCount = it)) }
                    OverrideLongField(
                        stringResource(R.string.settings_foreign_relay_bps_limit),
                        profile.foreignRelayBpsLimit,
                        settings.foreignRelayBpsLimit,
                        valid = { it >= 0 },
                        error = stringResource(R.string.settings_bps_invalid),
                    ) { onGlobalSettings(settings.copy(foreignRelayBpsLimit = it)) }
                    OverrideLongField(
                        stringResource(R.string.settings_instance_recv_bps_limit),
                        profile.instanceRecvBpsLimit,
                        settings.instanceRecvBpsLimit,
                        valid = { it >= 0 },
                        error = stringResource(R.string.settings_bps_invalid),
                    ) { onGlobalSettings(settings.copy(instanceRecvBpsLimit = it)) }
                    OverrideLongField(
                        stringResource(R.string.settings_socket_mark),
                        profile.socketMark ?: 0L,
                        settings.socketMark,
                        valid = { it in 0..0xFFFFFFFFL },
                        error = stringResource(R.string.settings_socket_mark_invalid),
                        hint = stringResource(R.string.settings_socket_mark_description),
                    ) { onGlobalSettings(settings.copy(socketMark = it)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun OverrideItem(
    label: String,
    profileSummary: String,
    overridden: Boolean,
    onOverrideChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The whole header row is tappable so toggling never misses the small
        // switch; the Switch consumes its own taps, so there is no double fire.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = overridden,
                    role = Role.Switch,
                    onValueChange = onOverrideChange
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (overridden) stringResource(R.string.global_override_active) else profileSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.global_override),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = overridden,
                onCheckedChange = { on ->
                    Log.d("EasyTierOverride", "switch: $label -> $on")
                    onOverrideChange(on)
                },
            )
        }
        if (overridden) content()
    }
}

@Composable
private fun OverrideStringField(
    label: String,
    profileValue: String?,
    current: String?,
    onSet: (String?) -> Unit,
) {
    var input by remember(current) { mutableStateOf(current.orEmpty()) }
    OverrideItem(
        label = label,
        profileSummary = profileSummaryOrNotSet(profileValue),
        overridden = current != null,
        // Turning the override on always creates a non-null override: if the
        // profile value is blank, start from an empty editable field instead of
        // treating blank as "inherit" (which would make the switch a no-op).
        onOverrideChange = { on -> onSet(if (on) profileValue?.takeIf { it.isNotBlank() } ?: "" else null) },
    ) {
        FormField(label, input, null) { v ->
            input = v
            onSet(v.ifBlank { null })
        }
    }
}

@Composable
private fun OverrideBoolField(
    label: String,
    profileValue: Boolean,
    current: Boolean?,
    onSet: (Boolean?) -> Unit,
) {
    OverrideItem(
        label = label,
        profileSummary = profileSummaryBool(profileValue),
        overridden = current != null,
        onOverrideChange = { on -> onSet(if (on) profileValue else null) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Switch(checked = current ?: profileValue, onCheckedChange = { onSet(it) })
        }
    }
}

@Composable
private fun OverrideIntField(
    label: String,
    profileValue: Int,
    current: Int?,
    digits: Int = 5,
    valid: (Int) -> Boolean = { true },
    error: String? = null,
    hint: String? = null,
    onSet: (Int?) -> Unit,
) {
    var input by remember(current) { mutableStateOf(current?.toString() ?: "") }
    val parsed = input.trim().toIntOrNull()
    OverrideItem(
        label = label,
        profileSummary = profileSummary(profileValue.toString()),
        overridden = current != null,
        onOverrideChange = { on -> onSet(if (on) profileValue else null) },
    ) {
        FormField(
            label,
            input,
            when {
                input.isBlank() -> null
                parsed == null || !valid(parsed) -> error
                else -> null
            },
        ) { v ->
            input = v.filter { c -> c.isDigit() }.take(digits)
            if (input.isBlank()) onSet(null)
            else input.trim().toIntOrNull()?.takeIf(valid)?.let(onSet)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverrideLongField(
    label: String,
    profileValue: Long,
    current: Long?,
    digits: Int = 19,
    valid: (Long) -> Boolean = { true },
    error: String? = null,
    hint: String? = null,
    onSet: (Long?) -> Unit,
) {
    var input by remember(current) { mutableStateOf(current?.toString() ?: "") }
    val parsed = input.trim().toLongOrNull()
    OverrideItem(
        label = label,
        profileSummary = profileSummary(profileValue.toString()),
        overridden = current != null,
        onOverrideChange = { on -> onSet(if (on) profileValue else null) },
    ) {
        FormField(
            label,
            input,
            when {
                input.isBlank() -> null
                parsed == null || !valid(parsed) -> error
                else -> null
            },
        ) { v ->
            input = v.filter { c -> c.isDigit() }.take(digits)
            if (input.isBlank()) onSet(null)
            else input.trim().toLongOrNull()?.takeIf(valid)?.let(onSet)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverrideChoiceField(
    label: String,
    profileValue: String,
    current: String?,
    options: List<ChoiceOption>,
    onSet: (String?) -> Unit,
) {
    OverrideItem(
        label = label,
        profileSummary = profileSummary(profileValue),
        overridden = current != null,
        onOverrideChange = { on -> onSet(if (on) profileValue else null) },
    ) {
        ChoiceRow(label, current ?: profileValue, options) { onSet(it) }
    }
}

@Composable
private fun OverrideListField(
    label: String,
    profileValue: List<String>,
    current: List<String>?,
    onSet: (List<String>?) -> Unit,
) {
    OverrideItem(
        label = label,
        profileSummary = profileSummaryCount(profileValue.size),
        overridden = current != null,
        onOverrideChange = { on -> onSet(if (on) profileValue else null) },
    ) {
        ListField(label, current ?: profileValue, null) { onSet(it) }
    }
}

@Composable
private fun profileSummaryOrNotSet(value: String?): String = profileSummary(
    value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.global_value_not_set),
)

@Composable
private fun profileSummary(value: String): String = stringResource(R.string.global_profile_summary, value)

@Composable
private fun profileSummaryBool(value: Boolean): String = profileSummary(
    stringResource(if (value) R.string.global_value_on else R.string.global_value_off),
)

@Composable
private fun profileSummaryCount(count: Int): String = profileSummary(
    pluralStringResource(R.plurals.list_entry_count, count, count),
)

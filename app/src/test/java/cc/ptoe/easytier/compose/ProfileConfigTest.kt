package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork
import cc.ptoe.easytier.compose.data.SecureMode
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConfigTest {
    private fun profile(
        dhcp: Boolean = false,
        mode: TunMode = TunMode.VPN_SERVICE,
    ) = EasyTierProfile(
        id = "profile-1",
        name = "Lab",
        networkName = "lab",
        networkSecret = "secret",
        peers = listOf(Peer(uri = "tcp://192.0.2.1:11010")),
        listeners = listOf("tcp://0.0.0.0:11010"),
        virtualIpv4 = if (dhcp) null else "10.10.0.2/24",
        dhcp = dhcp,
        proxyNetworks = listOf(ProxyNetwork(cidr = "192.168.0.0/16")),
        manualRoutes = listOf("10.20.0.0/16"),
        enableMagicDns = true,
        mtu = 1380,
        tunMode = mode,
    )

    @Test
    fun `structured static profile emits reference TOML`() {
        val toml = TomlConfigBuilder.build(profile())

        assertTrue(toml.contains("instance_name = \"profile-1\""))
        assertTrue(toml.contains("dhcp = false"))
        assertTrue(toml.contains("ipv4 = \"10.10.0.2/24\""))
        assertTrue(toml.contains("listeners = [\"tcp://0.0.0.0:11010\"]"))
        assertTrue(toml.contains("routes = [\"10.20.0.0/16\"]"))
        assertTrue(toml.contains("[[peer]]\nuri = \"tcp://192.0.2.1:11010\""))
        assertTrue(toml.contains("[network_identity]\nnetwork_name = \"lab\""))
        assertTrue(toml.contains("[[proxy_network]]\ncidr = \"192.168.0.0/16\""))
        assertTrue(toml.contains("dev_name = \"easytier0\""))
        assertTrue(toml.contains("no_tun = false"))
        assertTrue(toml.contains("mtu = 1380"))
        assertTrue(toml.contains("accept_dns = true"))
    }

    @Test
    fun `dhcp omits static ipv4`() {
        val toml = TomlConfigBuilder.build(profile(dhcp = true))
        assertTrue(toml.contains("dhcp = true"))
        assertFalse(toml.contains("ipv4 ="))
    }

    @Test
    fun `root tun suppresses structured magic dns`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.ROOT_TUN))
        assertTrue(toml.contains("accept_dns = false"))
    }

    @Test
    fun `global settings override tun device name and no tun`() {
        val settings = GlobalSettings(tunDeviceName = "mytun", noTun = true, socks5AllowLan = false, socks5Port = 1080)
        val toml = TomlConfigBuilder.build(profile(), settings)
        assertTrue(toml.contains("dev_name = \"mytun\""))
        assertTrue(toml.contains("no_tun = true"))
        assertTrue(toml.contains("socks5_proxy = \"socks5://127.0.0.1:1080\""))
    }

    @Test
    fun `socks5 allow lan binds to wildcard`() {
        val settings = GlobalSettings(socks5AllowLan = true, socks5Port = 1080)
        val toml = TomlConfigBuilder.build(profile(), settings)
        assertTrue(toml.contains("socks5_proxy = \"socks5://0.0.0.0:1080\""))
    }

    @Test
    fun `socks5 port is reflected in toml`() {
        val settings = GlobalSettings(socks5AllowLan = false, socks5Port = 9999)
        val toml = TomlConfigBuilder.build(profile(), settings)
        assertTrue(toml.contains("socks5_proxy = \"socks5://127.0.0.1:9999\""))
    }

    @Test
    fun `root tun spec carries dev name override`() {
        val settings = GlobalSettings(tunDeviceName = "root-tun")
        val spec = TomlConfigBuilder.rootTunSpec(profile(mode = TunMode.ROOT_TUN), settings)
        assertEquals("root-tun", spec.devName)
    }

    @Test
    fun `root tun spec falls back to easytier0 when blank`() {
        val settings = GlobalSettings(tunDeviceName = "  ")
        val spec = TomlConfigBuilder.rootTunSpec(profile(mode = TunMode.ROOT_TUN), settings)
        assertEquals("easytier0", spec.devName)
    }

    @Test
    fun `validator blocks invalid structured and native fields`() {
        val invalid = profile().copy(
            virtualIpv4 = "10.10.0.2/99",
            mtu = 400,
            peers = listOf(Peer(uri = "")),
        )
        val errors = ProfileValidator(NativeConfigParser { "native parse failed" }).validate(invalid)
        assertTrue(errors.containsKey("virtualIpv4"))
        assertTrue(errors.containsKey("mtu"))
        assertTrue(errors.containsKey("peers"))
        assertEquals("native parse failed", errors["form"])
    }

    @Test
    fun `validator rejects invalid socks5 port`() {
        val settings = GlobalSettings(socks5Port = 0)
        val errors = ProfileValidator(NativeConfigParser { null }).validate(profile(), settings)
        assertTrue(errors.containsKey("globalSettings"))
    }

    @Test
    fun `validator accepts valid profile with new fields`() {
        val full = profile().copy(
            hostname = "node-1",
            virtualIpv6 = "fd00::1/64",
            portForwards = listOf(PortForward(bindAddr = "0.0.0.0:8080", dstAddr = "10.0.0.1:80", proto = "tcp")),
            vpnPortal = VpnPortal(clientCidr = "10.99.0.0/24", wireguardListen = "0.0.0.0:51820"),
            stunServers = listOf("stun://stun.l.google.com:19302"),
            dataCompressAlgo = CompressionAlgo.Zstd,
            encryptionAlgorithm = EncryptionAlgorithm.ChaCha20,
            multiThreadCount = 4,
            enableKcpProxy = true,
            enableExitNode = true,
            exitNodes = listOf("10.20.0.1"),
            secureMode = SecureMode(enabled = true, localPrivateKey = "priv", localPublicKey = "pub"),
        )
        val errors = ProfileValidator(NativeConfigParser { null }).validate(full)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `validator blocks invalid port forward and vpn portal`() {
        val invalid = profile().copy(
            portForwards = listOf(PortForward(bindAddr = "bad", dstAddr = "10.0.0.1:80", proto = "tcp")),
            vpnPortal = VpnPortal(clientCidr = "not-a-cidr", wireguardListen = "0.0.0.0:51820"),
        )
        val errors = ProfileValidator(NativeConfigParser { null }).validate(invalid)
        assertTrue(errors.containsKey("portForwards"))
        assertTrue(errors.containsKey("vpnPortal"))
    }

    @Test
    fun `validator blocks invalid proxy networks`() {
        val invalid = profile().copy(
            proxyNetworks = listOf(ProxyNetwork(cidr = "not-a-cidr")),
        )
        val errors = ProfileValidator(NativeConfigParser { null }).validate(invalid)
        assertTrue(errors.containsKey("proxyNetworks"))
    }

    @Test
    fun `validator rejects negative bps limits and zero thread count`() {
        val invalid = profile().copy(
            multiThreadCount = 0,
            foreignRelayBpsLimit = -1,
            instanceRecvBpsLimit = -5,
        )
        val errors = ProfileValidator(NativeConfigParser { null }).validate(invalid)
        assertTrue(errors.containsKey("multiThreadCount"))
        assertTrue(errors.containsKey("foreignRelayBpsLimit"))
        assertTrue(errors.containsKey("instanceRecvBpsLimit"))
    }

    @Test
    fun `toml builder emits all flag fields`() {
        val toml = TomlConfigBuilder.build(profile().copy(enableKcpProxy = true, enableQuicProxy = true))
        assertTrue(toml.contains("enable_kcp_proxy = true"))
        assertTrue(toml.contains("enable_quic_proxy = true"))
        assertTrue(toml.contains("data_compress_algo = \"None\""))
        assertTrue(toml.contains("encryption_algorithm = \"aes-gcm\""))
        assertTrue(toml.contains("tld_dns_zone = \"et.net.\""))
        assertTrue(toml.contains("default_protocol = \"tcp\""))
    }

    @Test
    fun `toml builder emits port forward and vpn portal sections`() {
        val p = profile().copy(
            portForwards = listOf(PortForward(bindAddr = "0.0.0.0:8080", dstAddr = "10.0.0.1:80", proto = "tcp")),
            vpnPortal = VpnPortal(clientCidr = "10.99.0.0/24", wireguardListen = "0.0.0.0:51820"),
        )
        val toml = TomlConfigBuilder.build(p)
        assertTrue(toml.contains("[[port_forward]]"))
        assertTrue(toml.contains("bind_addr = \"0.0.0.0:8080\""))
        assertTrue(toml.contains("[vpn_portal_config]"))
        assertTrue(toml.contains("client_cidr = \"10.99.0.0/24\""))
    }

    @Test
    fun `toml builder emits secure mode section when enabled`() {
        val p = profile().copy(secureMode = SecureMode(enabled = true, localPrivateKey = "k1", localPublicKey = "k2"))
        val toml = TomlConfigBuilder.build(p)
        assertTrue(toml.contains("[secure_mode]"))
        assertTrue(toml.contains("enabled = true"))
        assertTrue(toml.contains("local_private_key = \"k1\""))
    }
}

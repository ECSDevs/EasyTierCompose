package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.core.ValidationMessage
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
import cc.ptoe.easytier.compose.data.mergeInto
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
    fun `root tun supports magic dns`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.ROOT_TUN))
        assertTrue(toml.contains("accept_dns = true"))
    }

    @Test
    fun `root tun spec carries magic dns flag`() {
        val spec = TomlConfigBuilder.rootTunSpec(profile(mode = TunMode.ROOT_TUN))
        assertTrue(spec.magicDns)
    }

    @Test
    fun `root tun spec carries magic dns disabled`() {
        val spec = TomlConfigBuilder.rootTunSpec(profile(mode = TunMode.ROOT_TUN).copy(enableMagicDns = false))
        assertFalse(spec.magicDns)
    }

    @Test
    fun `global settings override tun device name`() {
        val settings = GlobalSettings(tunDeviceName = "mytun")
        val toml = TomlConfigBuilder.build(profile(), settings)
        assertTrue(toml.contains("dev_name = \"mytun\""))
    }

    @Test
    fun `no tun mode forces bind_device false`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.NO_TUN))
        assertTrue(toml.contains("no_tun = true"))
        assertTrue(toml.contains("bind_device = false"))
        assertFalse(toml.contains("bind_device = true"))
    }

    @Test
    fun `root tun mode applies fwmark`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.ROOT_TUN))
        assertTrue(toml.contains("bind_device = false"))
        // Root mode always applies the fwmark (the core runs in the root daemon).
        assertTrue(toml.contains("socket_mark ="))
    }

    @Test
    fun `no tun mode emits no socket mark and disables magic dns`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.NO_TUN))
        assertFalse(toml.contains("socket_mark ="))
        assertTrue(toml.contains("accept_dns = false"))
    }

    @Test
    fun `vpn service with tun enabled preserves bind_device true`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.VPN_SERVICE).copy(bindDevice = true))
        assertTrue(toml.contains("bind_device = true"))
    }

    @Test
    fun `no tun mode forces accept_dns false even when magic dns enabled`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.NO_TUN).copy(enableMagicDns = true))
        assertTrue(toml.contains("accept_dns = false"))
    }

    @Test
    fun `no tun mode preserves accept_dns false when magic dns disabled`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.NO_TUN).copy(enableMagicDns = false))
        assertTrue(toml.contains("accept_dns = false"))
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
            peers = listOf(Peer(uri = "")),
        )
        val errors = ProfileValidator(NativeConfigParser { ValidationMessage.Raw("native parse failed") })
            .validate(invalid, GlobalSettings(mtu = 400))
        assertTrue(errors.containsKey("virtualIpv4"))
        assertTrue(errors.containsKey("globalMtu"))
        assertTrue(errors.containsKey("peers"))
        assertEquals(ValidationMessage.Raw("native parse failed"), errors["form"])
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
            enableKcpProxy = true,
            enableExitNode = true,
            exitNodes = listOf("10.20.0.1"),
            secureMode = SecureMode(enabled = true, localPrivateKey = "priv", localPublicKey = "pub"),
        )
        val settings = GlobalSettings(multiThreadCount = 4)
        val errors = ProfileValidator(NativeConfigParser { null }).validate(full, settings)
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
    fun `validator blocks non-routable vpn portal client cidr`() {
        listOf(
            "127.0.0.1/32",   // loopback: reply packets die on peers' lo
            "169.254.10.0/24", // link-local
            "224.0.0.0/24",   // multicast
            "0.0.0.0/0",      // unspecified
            "10.99.0.0/30",   // prefix too small for client addresses
        ).forEach { cidr ->
            val invalid = profile().copy(
                vpnPortal = VpnPortal(clientCidr = cidr, wireguardListen = "0.0.0.0:51820"),
            )
            val errors = ProfileValidator(NativeConfigParser { null }).validate(invalid)
            assertTrue("expected $cidr to be rejected, errors: $errors", errors.containsKey("vpnPortal"))
        }
    }

    @Test
    fun `validator accepts generated vpn portal defaults`() {
        repeat(32) {
            val portal = VpnPortal.generateDefault()
            val errors = ProfileValidator(NativeConfigParser { null })
                .validate(profile().copy(vpnPortal = portal))
            assertTrue("generated portal invalid: $portal -> $errors", !errors.containsKey("vpnPortal"))
        }
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
        val invalidSettings = GlobalSettings(
            multiThreadCount = 0,
            foreignRelayBpsLimit = -1,
            instanceRecvBpsLimit = -5,
        )
        val errors = ProfileValidator(NativeConfigParser { null }).validate(profile(), invalidSettings)
        assertTrue(errors.containsKey("globalMultiThreadCount"))
        assertTrue(errors.containsKey("globalForeignRelayBpsLimit"))
        assertTrue(errors.containsKey("globalInstanceRecvBpsLimit"))
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

    @Test
    fun `toml builder emits socks5 proxy when set`() {
        val toml = TomlConfigBuilder.build(profile().copy(socks5Proxy = "socks5://0.0.0.0:1080"))
        assertTrue(toml.contains("socks5_proxy = \"socks5://0.0.0.0:1080\""))
    }

    @Test
    fun `toml builder omits socks5 proxy when blank`() {
        val toml = TomlConfigBuilder.build(profile().copy(socks5Proxy = null))
        assertFalse(toml.contains("socks5_proxy"))
    }

    @Test
    fun `toml builder emits need p2p flag`() {
        val toml = TomlConfigBuilder.build(profile().copy(needP2p = true))
        assertTrue(toml.contains("need_p2p = true"))
    }

    @Test
    fun `toml builder applies custom socket mark in root tun`() {
        val settings = GlobalSettings(socketMark = 66)
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.ROOT_TUN), settings)
        assertTrue(toml.contains("socket_mark = 66"))
    }

    @Test
    fun `toml builder keeps default socket mark when unset`() {
        val toml = TomlConfigBuilder.build(profile(mode = TunMode.ROOT_TUN))
        assertTrue(toml.contains("socket_mark = ${TomlConfigBuilder.ROOT_TUN_SOCKET_MARK}"))
    }

    @Test
    fun `validator accepts valid socks5 proxy and socket mark`() {
        val full = profile().copy(socks5Proxy = "socks5://0.0.0.0:1080", needP2p = true)
        val settings = GlobalSettings(socketMark = 0x20000)
        val errors = ProfileValidator(NativeConfigParser { null }).validate(full, settings)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `validator blocks invalid socks5 proxy`() {
        listOf(
            "http://0.0.0.0:1080",   // wrong scheme
            "socks5://0.0.0.0",      // missing port
            "socks5://:1080",        // missing host
            "not-a-url",
        ).forEach { proxy ->
            val errors = ProfileValidator(NativeConfigParser { null })
                .validate(profile().copy(socks5Proxy = proxy))
            assertTrue("expected $proxy to be rejected, errors: $errors", errors.containsKey("socks5Proxy"))
        }
    }

    @Test
    fun `validator blocks out of range socket mark`() {
        val settings = GlobalSettings(socketMark = -1)
        val errors = ProfileValidator(NativeConfigParser { null }).validate(profile(), settings)
        assertTrue(errors.containsKey("globalSocketMark"))
    }

    @Test
    fun `profile carries its own device-local values without overrides`() {
        val toml = TomlConfigBuilder.build(profile().copy(mtu = 1500, tunDeviceName = "mydev", multiThreadCount = 4))
        assertTrue(toml.contains("mtu = 1500"))
        assertTrue(toml.contains("dev_name = \"mydev\""))
        assertTrue(toml.contains("multi_thread_count = 4"))
    }

    @Test
    fun `global override wins over profile device value`() {
        val settings = GlobalSettings(mtu = 1400)
        val toml = TomlConfigBuilder.build(profile().copy(mtu = 1500), settings)
        assertTrue(toml.contains("mtu = 1400"))
        assertFalse(toml.contains("mtu = 1500"))
    }

    @Test
    fun `empty global settings keep profile network values`() {
        val toml = TomlConfigBuilder.build(profile().copy(networkName = "my-net"))
        assertTrue(toml.contains("network_name = \"my-net\""))
    }

    @Test
    fun `global override replaces profile network name`() {
        val settings = GlobalSettings(networkName = "override-net")
        val toml = TomlConfigBuilder.build(profile(), settings)
        assertTrue(toml.contains("network_name = \"override-net\""))
    }

    @Test
    fun `merge into profile is idempotent for empty overrides`() {
        val p = profile()
        val merged = GlobalSettings().mergeInto(p)
        assertEquals(p, merged)
    }

    @Test
    fun `merge into applies boolean and enum overrides`() {
        val settings = GlobalSettings(
            enableMagicDns = true,
            disableP2p = true,
            dataCompressAlgo = CompressionAlgo.Zstd,
            encryptionAlgorithm = EncryptionAlgorithm.ChaCha20,
        )
        val merged = settings.mergeInto(profile())
        assertTrue(merged.enableMagicDns)
        assertTrue(merged.disableP2p)
        assertEquals(CompressionAlgo.Zstd, merged.dataCompressAlgo)
        assertEquals(EncryptionAlgorithm.ChaCha20, merged.encryptionAlgorithm)
    }
}

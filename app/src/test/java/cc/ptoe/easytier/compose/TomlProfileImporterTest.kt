package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.core.TomlImportResult
import cc.ptoe.easytier.compose.core.TomlProfileImporter
import cc.ptoe.easytier.compose.data.Acl
import cc.ptoe.easytier.compose.data.AclAction
import cc.ptoe.easytier.compose.data.AclChain
import cc.ptoe.easytier.compose.data.AclChainType
import cc.ptoe.easytier.compose.data.AclGroup
import cc.ptoe.easytier.compose.data.AclGroupDeclare
import cc.ptoe.easytier.compose.data.AclProtocol
import cc.ptoe.easytier.compose.data.AclRule
import cc.ptoe.easytier.compose.data.CompressionAlgo
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.EncryptionAlgorithm
import cc.ptoe.easytier.compose.data.ManagedCredential
import cc.ptoe.easytier.compose.data.Peer
import cc.ptoe.easytier.compose.data.PortForward
import cc.ptoe.easytier.compose.data.ProxyNetwork
import cc.ptoe.easytier.compose.data.SecureMode
import cc.ptoe.easytier.compose.data.TunMode
import cc.ptoe.easytier.compose.data.VpnPortal
import cc.ptoe.easytier.compose.data.VpnPortalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlProfileImporterTest {

    @Test
    fun `imports a typical easytier config`() {
        val toml = """
            hostname = "node-a"
            dhcp = true
            listeners = ["tcp://0.0.0.0:11010"]
            stun_servers = ["stun.example.com:3478"]

            [network_identity]
            network_name = "mesh"
            network_secret = "topsecret"

            [[peer]]
            uri = "tcp://192.0.2.10:11010"

            [[peer]]
            uri = "wss://relay.example.com"

            [flags]
            mtu = 1400
            default_protocol = "tcp"
            enable_encryption = true
            data_compress_algo = "Zstd"
            encryption_algorithm = "chacha20"
            no_tun = true
        """.trimIndent()

        val profile = importSuccess(toml)

        assertEquals("mesh", profile.networkName)
        assertEquals("topsecret", profile.networkSecret)
        assertEquals("node-a", profile.hostname)
        assertEquals("node-a", profile.name) // instance_name absent → hostname
        assertTrue(profile.dhcp)
        assertEquals(
            listOf("tcp://192.0.2.10:11010", "wss://relay.example.com"),
            profile.peers.map { it.uri },
        )
        assertEquals(listOf("tcp://0.0.0.0:11010"), profile.listeners)
        assertEquals(listOf("stun.example.com:3478"), profile.stunServers)
        assertEquals(1400, profile.mtu)
        assertEquals(CompressionAlgo.Zstd, profile.dataCompressAlgo)
        assertEquals(EncryptionAlgorithm.ChaCha20, profile.encryptionAlgorithm)
        // A config with no TUN toggle stays at the VPN service default.
        assertNull(profile.virtualIpv4)
        assertEquals(TunMode.NO_TUN, profile.tunMode)
    }

    @Test
    fun `imports nested sections - peers, portal, credentials, acl`() {
        val toml = """
            instance_name = "gateway"
            [network_identity]
            network_name = "mesh"
            network_secret = "secret"

            [[peer]]
            uri = "tcp://10.0.0.1:11010"
            peer_public_key = "pub-key"

            [[proxy_network]]
            cidr = "192.168.10.0/24"
            mapped_cidr = "172.16.10.0/24"
            allow = ["tcp", "udp"]

            [[port_forward]]
            bind_addr = "0.0.0.0:8080"
            dst_addr = "10.200.0.5:80"
            proto = "tcp"

            [vpn_portal_config]
            wireguard_listen = "0.0.0.0:11011"
            wireguard_private_key = "wg-priv"

            [[vpn_portal_config.clients]]
            name = "phone"
            virtual_ip = "100.64.0.5"
            groups = ["mobile"]

            [secure_mode]
            enabled = true
            local_private_key = "priv-key"
            local_public_key = "pub-key"

            [[managed_credentials]]
            credential_id = "cred-1"
            credential_secret = "cred-secret"
            groups = ["admin"]
            allow_relay = true
            expiry_unix = 2000000000

            [acl.acl_v1]
            [[acl.acl_v1.chains]]
            name = "inbound"
            chain_type = 1
            default_action = 2

            [[acl.acl_v1.chains.rules]]
            name = "allow-ssh"
            protocol = 1
            action = 1
            enabled = true
            source_ips = ["10.0.0.0/8"]
            source_ports = ["22"]
        """.trimIndent()

        val profile = importSuccess(toml)

        assertEquals("gateway", profile.name) // instance_name preserved as display name
        assertEquals(Peer(uri = "tcp://10.0.0.1:11010", peerPublicKey = "pub-key"), profile.peers.single())
        assertEquals(
            ProxyNetwork(cidr = "192.168.10.0/24", mappedCidr = "172.16.10.0/24", allow = listOf("tcp", "udp")),
            profile.proxyNetworks.single(),
        )
        assertEquals(PortForward("0.0.0.0:8080", "10.200.0.5:80", "tcp"), profile.portForwards.single())
        assertEquals(
            VpnPortal(
                wireguardListen = "0.0.0.0:11011",
                wireguardPrivateKey = "wg-priv",
                clients = listOf(VpnPortalClient(name = "phone", virtualIp = "100.64.0.5", groups = listOf("mobile"))),
            ),
            profile.vpnPortal,
        )
        assertEquals(
            SecureMode(enabled = true, localPrivateKey = "priv-key", localPublicKey = "pub-key"),
            profile.secureMode,
        )
        assertEquals(
            ManagedCredential(
                credentialId = "cred-1",
                credentialSecret = "cred-secret",
                groups = listOf("admin"),
                allowRelay = true,
                expiryUnix = 2_000_000_000L,
            ),
            profile.managedCredentials.single(),
        )
        val chain = profile.acl?.chains?.single()
        assertEquals(AclChainType.Inbound, chain?.chainType)
        assertEquals(AclAction.Drop, chain?.defaultAction)
        val rule = chain?.rules?.single()
        assertEquals(
            AclRule(
                name = "allow-ssh",
                enabled = true,
                protocol = AclProtocol.TCP,
                sourceIps = listOf("10.0.0.0/8"),
                sourcePorts = listOf("22"),
            ),
            rule,
        )
    }

    @Test
    fun `export - import - export round trip preserves the TOML`() {
        val original = EasyTierProfile(
            id = "profile-1",
            name = "Lab",
            hostname = "node-a",
            networkName = "lab",
            networkSecret = "secret",
            peers = listOf(Peer(uri = "tcp://192.0.2.1:11010")),
            listeners = listOf("tcp://0.0.0.0:11010"),
            mappedListeners = listOf("tcp://198.51.100.1:11010"),
            virtualIpv4 = "10.10.0.2/24",
            virtualIpv6 = "fd00::2/64",
            dhcp = false,
            proxyNetworks = listOf(ProxyNetwork(cidr = "192.168.0.0/16", allow = listOf("tcp"))),
            manualRoutes = listOf("10.20.0.0/16"),
            exitNodes = listOf("10.10.0.9"),
            enableMagicDns = true,
            tunMode = TunMode.ROOT_TUN,
            ipv6PublicAddrProvider = true,
            ipv6PublicAddrPrefix = "2001:db8::/64",
            portForwards = listOf(PortForward("0.0.0.0:8080", "10.10.0.9:80", "tcp")),
            socks5Proxy = "socks5://0.0.0.0:1080",
            secureMode = SecureMode(enabled = true, localPrivateKey = "priv", localPublicKey = "pub"),
            stunServers = listOf("stun.example.com:3478"),
            tldDnsZone = "et.net.",
            dataCompressAlgo = CompressionAlgo.Zstd,
            encryptionAlgorithm = EncryptionAlgorithm.Aes256Gcm,
            foreignRelayBpsLimit = 12_345L,
            instanceRecvBpsLimit = 67_890L,
            socketMark = 131_072L,
            acl = Acl(
                chains = listOf(
                    AclChain(
                        name = "fwd",
                        chainType = AclChainType.Forward,
                        defaultAction = AclAction.Allow,
                        rules = listOf(
                            AclRule(
                                name = "r",
                                enabled = true,
                                protocol = AclProtocol.TCP,
                                ports = listOf("80"),
                                sourceIps = listOf("10.0.0.0/8"),
                                action = AclAction.Allow,
                            ),
                        ),
                    ),
                ),
                group = AclGroup(declares = listOf(AclGroupDeclare("admin", "pw")), members = listOf("admin")),
            ),
            managedCredentials = listOf(ManagedCredential(credentialId = "c", credentialSecret = "s")),
        )
        val exported = TomlConfigBuilder.build(original)

        val imported = importSuccess(exported)
        assertEquals(TunMode.ROOT_TUN, imported.tunMode) // socket_mark implies root
        assertEquals(131_072L, imported.socketMark)
        assertEquals(EncryptionAlgorithm.Aes256Gcm, imported.encryptionAlgorithm)

        // Same instance identity → re-export must byte-for-byte equal the input.
        val rebuilt = TomlConfigBuilder.build(imported.copy(id = original.id))
        assertEquals(exported, rebuilt)
    }

    @Test
    fun `no tun flag maps to NO_TUN mode`() {
        val toml = """
            [network_identity]
            network_name = "mesh"
            [flags]
            no_tun = true
        """.trimIndent()
        assertEquals(TunMode.NO_TUN, importSuccess(toml).tunMode)
    }

    @Test
    fun `tolerates utf8 bom and null bytes`() {
        // UTF-8 BOM prefix plus NULs interleaved the way a UTF-16 file decoded
        // as UTF-8 would produce. Both must be stripped before parsing.
        val toml = "\uFEFF[network_identity]\nnetwork_name = \"mesh\"\nnetwork_secret = \"s\"\n"
            .replace("\n", "\u0000\n")
        val profile = importSuccess(toml)
        assertEquals("mesh", profile.networkName)
    }

    @Test
    fun `rejects invalid toml`() {
        val result = TomlProfileImporter.import("[network_identity")
        assertTrue(result is TomlImportResult.Failure)
    }

    @Test
    fun `rejects config without network identity`() {
        val result = TomlProfileImporter.import("hostname = \"x\"\n")
        assertTrue(result is TomlImportResult.Failure)
    }

    private fun importSuccess(toml: String): EasyTierProfile {
        val result = TomlProfileImporter.import(toml)
        assertTrue("Import failed: ${(result as? TomlImportResult.Failure)?.message}", result is TomlImportResult.Success)
        return (result as TomlImportResult.Success).profile
    }
}
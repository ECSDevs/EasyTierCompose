package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.toV2rayShareLink
import cc.ptoe.easytier.compose.data.WireGuardPortalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireGuardShareLinkTest {
    // Mirrors EasyTier Core's WireGuardPortal render_client_config output.
    private val clientConfig = """
        [Interface]
        PrivateKey = aB3+key/==
        Address = 10.26.26.2/32 # should assign an ip from this cidr manually

        [Peer]
        PublicKey = xY9+pub/==
        AllowedIPs = 10.26.0.0/16,192.168.0.0/16
        Endpoint = 203.0.113.7:11010 # should be the public ip(or domain) of the vpn server
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `share link carries private key endpoint public key and address`() {
        val info = WireGuardPortalInfo("wireguard", clientConfig)
        val link = info.toV2rayShareLink()
        assertEquals(
            "wireguard://aB3%2Bkey%2F%3D%3D@203.0.113.7:11010" +
                "?publickey=xY9%2Bpub%2F%3D%3D&address=10.26.26.2%2F32",
            link,
        )
    }

    @Test
    fun `share link appends url encoded remark`() {
        val info = WireGuardPortalInfo("wireguard", clientConfig)
        val link = info.toV2rayShareLink("My Profile")
        assertEquals(
            "wireguard://aB3%2Bkey%2F%3D%3D@203.0.113.7:11010" +
                "?publickey=xY9%2Bpub%2F%3D%3D&address=10.26.26.2%2F32#My+Profile",
            link,
        )
    }

    @Test
    fun `share link supports domain endpoint`() {
        val config = clientConfig.replace("203.0.113.7:11010", "vpn.example.com:51820")
        val link = WireGuardPortalInfo("wireguard", config).toV2rayShareLink()
        assertEquals(
            "wireguard://aB3%2Bkey%2F%3D%3D@vpn.example.com:51820" +
                "?publickey=xY9%2Bpub%2F%3D%3D&address=10.26.26.2%2F32",
            link,
        )
    }

    @Test
    fun `share link keeps bracketed ipv6 endpoint`() {
        val config = clientConfig.replace("203.0.113.7:11010", "[2001:db8::1]:51820")
        val link = WireGuardPortalInfo("wireguard", config).toV2rayShareLink()
        assertEquals(
            "wireguard://aB3%2Bkey%2F%3D%3D@[2001:db8::1]:51820" +
                "?publickey=xY9%2Bpub%2F%3D%3D&address=10.26.26.2%2F32",
            link,
        )
    }

    @Test
    fun `share link fails without private key or endpoint`() {
        assertNull(WireGuardPortalInfo("wireguard", "[Peer]\nEndpoint = 1.2.3.4:1").toV2rayShareLink())
        assertNull(WireGuardPortalInfo("wireguard", "[Interface]\nPrivateKey = abc").toV2rayShareLink())
    }
}

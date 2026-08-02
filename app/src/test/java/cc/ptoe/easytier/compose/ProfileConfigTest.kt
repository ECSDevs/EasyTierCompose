package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.NativeConfigParser
import cc.ptoe.easytier.compose.core.ProfileValidator
import cc.ptoe.easytier.compose.core.TomlConfigBuilder
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.TunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileConfigTest {
    private fun profile(
        dhcp: Boolean = false,
        mode: TunMode = TunMode.VPN_SERVICE,
        advancedToml: String? = null,
    ) = EasyTierProfile(
        id = "profile-1",
        name = "Lab",
        networkName = "lab",
        networkSecret = "secret",
        peerUrls = listOf("tcp://192.0.2.1:11010"),
        listeners = listOf("tcp://0.0.0.0:11010"),
        virtualIpv4 = if (dhcp) null else "10.10.0.2/24",
        dhcp = dhcp,
        proxyCidrs = listOf("192.168.0.0/16"),
        manualRoutes = listOf("10.20.0.0/16"),
        enableMagicDns = true,
        mtu = 1380,
        tunMode = mode,
        advancedToml = advancedToml,
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
    fun `valid VPN advanced TOML is returned unchanged`() {
        val advanced = "instance_name = \"advanced\"\ndhcp = true"
        val validated = ProfileValidator(NativeConfigParser { null }).validate(profile(advancedToml = advanced))
        assertTrue(validated.isEmpty())
        assertEquals(advanced, TomlConfigBuilder.build(profile(advancedToml = advanced)))
    }

    @Test
    fun `validator blocks invalid structured and native fields`() {
        val invalid = profile().copy(
            virtualIpv4 = "10.10.0.2/99",
            mtu = 400,
            peerUrls = listOf(""),
        )
        val errors = ProfileValidator(NativeConfigParser { "native parse failed" }).validate(invalid)
        assertTrue(errors.containsKey("virtualIpv4"))
        assertTrue(errors.containsKey("mtu"))
        assertTrue(errors.containsKey("peerUrls"))

        val nativeErrors = ProfileValidator(NativeConfigParser { "native parse failed" })
            .validate(profile(advancedToml = "bad"))
        assertEquals("native parse failed", nativeErrors["advancedToml"])
    }

    @Test
    fun `root advanced TOML is blocked before native parsing`() {
        val errors = ProfileValidator(NativeConfigParser { "should not be called" })
            .validate(profile(mode = TunMode.ROOT_TUN, advancedToml = "instance_name = \"root\""))
        assertEquals("Advanced TOML is supported only by VPN Service", errors["advancedToml"])
        assertNull(errors["virtualIpv4"])
    }
}

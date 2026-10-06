package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.networkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInfoTest {

    // Shape mirrors the pbjson output of NetworkInstanceRunningInfoMap
    // (preserve_proto_field_names): bool fields appear as JSON booleans and are
    // omitted entirely when they carry the proto3 default (false).
    @Test
    fun `parses running info with running true`() {
        val json = """
            {"map":{"profile-1":{
                "running":true,
                "my_node_info":{"virtual_ipv4":{"address":{"addr":"167837958"},"network_length":24},
                    "hostname":"node-a","stun_info":{"udp_nat_type":5}},
                "routes":[{"proxy_cidrs":["192.168.0.0/16","10.0.0.1"]}],
                "peer_route_pairs":[]
            }}}
        """.trimIndent()
        val info = json.networkInfo("profile-1")
        assertTrue(info != null)
        assertEquals(true, info!!.running)
        assertEquals("10.1.1.6/24", info.virtualIpv4)
        assertEquals("node-a", info.hostname)
        assertEquals("PortRestricted", info.natType)
        assertEquals(listOf("10.0.0.1/32", "192.168.0.0/16"), info.routes)
    }

    @Test
    fun `parses entry with running false`() {
        val json = """
            {"map":{"profile-1":{"running":false}}}
        """.trimIndent()
        val info = json.networkInfo("profile-1")
        assertTrue(info != null)
        assertEquals(false, info!!.running)
        assertNull(info.virtualIpv4)
        assertNull(info.error)
    }

    // pbjson omits proto3-default bools: a stopped instance's entry has no
    // "running" key at all. The parser must surface null, not true.
    @Test
    fun `missing running field maps to null`() {
        val json = """
            {"map":{"profile-1":{"dev_name":"easytier0"}}}
        """.trimIndent()
        val info = json.networkInfo("profile-1")
        assertTrue(info != null)
        assertNull(info!!.running)
    }

    @Test
    fun `missing entry returns null`() {
        val json = """{"map":{}}"""
        assertNull(json.networkInfo("profile-1"))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull("not json".networkInfo("profile-1"))
    }

    @Test
    fun `error_msg is surfaced alongside running`() {
        val json = """
            {"map":{"profile-1":{"running":false,"error_msg":"bind failed"}}}
        """.trimIndent()
        val info = json.networkInfo("profile-1")
        assertTrue(info != null)
        assertEquals(false, info!!.running)
        assertEquals("bind failed", info.error)
    }
}

package cc.ptoe.easytier.compose.transport.root

import android.util.Log

/**
 * Manages the TUN routes and `ip rule` entries for the root EasyTier daemon.
 *
 * Routes include:
 *  - The virtual IP subnet (e.g. 10.126.126.0/24 from 10.126.126.1/24).
 *  - Remote peer proxy_cidrs (from collectNetworkInfos routes).
 *  - Manual routes from the profile config.
 *  - Magic DNS route (100.100.100.101/32) when enabled.
 *
 * Note: spec.proxyCidrs (local [[proxy_network]] entries) are intentionally
 * NOT added — those are networks this node advertises to peers, not networks
 * we route through the TUN.
 *
 * @param devName TUN device name (e.g. "easytier0").
 * @param execRoot executes a shell command as root in the current (main) namespace;
 *   returns the command's stdout (or null on failure).
 */
internal class RootTunRouteManager(
    private val devName: String,
    private val execRoot: (String) -> String?,
) {
    // Tracks ip rule priorities we installed so cleanup can remove them.
    private val installedRules = mutableListOf<Int>()
    // TUN routes we added to easytier0, so cleanup can remove exactly the set we installed.
    private val installedRoutes = mutableSetOf<String>()

    fun syncTunRoutes(cidr: String, runtimeRoutes: List<String>, spec: RootTunSpec) {
        val subnet = virtualIpSubnet(cidr)
        val magicDnsRoute = if (spec.magicDns) listOf(MAGIC_DNS_ROUTE) else emptyList()
        val allRoutes = (listOfNotNull(subnet) + runtimeRoutes + spec.manualRoutes + magicDnsRoute)
            .filter(String::isNotBlank).distinct().sorted()
        Log.i(TAG, "syncTunRoutes: ${allRoutes.size} routes: $allRoutes")
        for (r in (installedRoutes - allRoutes).toList()) {
            execRoot("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
        for (r in allRoutes - installedRoutes) {
            val out = execRoot("ip route add $r dev $devName 2>&1").orEmpty()
            if (out.isBlank() || out.contains("File exists")) {
                installedRoutes.add(r)
            } else {
                Log.w(TAG, "syncTunRoutes: add $r failed: ${out.trim()}")
            }
        }
        installTunRules(allRoutes)
    }

    /** Removes easytier0 routes, ip rules installed by this manager. Does NOT delete the interface. */
    fun cleanupRoutesAndRules() {
        removeAllTunRules()
        for (r in installedRoutes.toList()) {
            execRoot("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
    }

    /** Computes the network subnet CIDR from an IPv4 CIDR, e.g. "10.126.126.1/24" -> "10.126.126.0/24". */
    private fun virtualIpSubnet(cidr: String): String? {
        val slash = cidr.indexOf('/')
        if (slash < 0) return null
        val ip = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return null
        var addr = ((octets[0] and 0xFF) shl 24) or ((octets[1] and 0xFF) shl 16) or
            ((octets[2] and 0xFF) shl 8) or (octets[3] and 0xFF)
        val mask = if (prefix == 0) 0 else ((-1 shl (32 - prefix)))
        addr = addr and mask
        return "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}/$prefix"
    }

    /**
     * Installs `ip rule add to <cidr> lookup main` entries with priority 5000
     * (ahead of Android's per-interface rules at 10000+) so Android's
     * per-interface routing tables don't intercept traffic destined for the TUN.
     * Idempotent: only adds rules for CIDRs not already installed.
     */
    private fun installTunRules(cidrs: List<String>) {
        val wanted = cidrs.filter(String::isNotBlank).distinct().sorted()
        val removedCidrs = currentRuleCidrs() - wanted.toSet()
        for (cidr in removedCidrs) removeRuleForCidr(cidr)
        val existing = currentRuleCidrs()
        var nextPriority = IP_RULE_PRIORITY_START + installedRules.size
        for (cidr in wanted) {
            if (cidr in existing) continue
            val priority = nextPriority++
            runCatching { execRoot("ip rule add to $cidr lookup main priority $priority") }
                .onFailure { Log.w(TAG, "installTunRules: add rule for $cidr failed: ${it.message}") }
                .onSuccess {
                    installedRules.add(priority)
                    Log.i(TAG, "installTunRules: added rule priority=$priority to $cidr lookup main")
                }
        }
    }

    /** Returns the set of CIDRs that currently have an ip rule installed by us. */
    private fun currentRuleCidrs(): Set<String> = runCatching {
        val output = execRoot("ip rule show") ?: return@runCatching emptySet()
        output.lineSequence()
            .mapNotNull { line ->
                val toMatch = Regex("""to\s+(\S+)""").find(line)?.groupValues?.getOrNull(1)
                val isMain = line.contains("lookup main")
                if (toMatch != null && isMain) toMatch else null
            }
            .toSet()
    }.getOrDefault(emptySet())

    private fun removeRuleForCidr(cidr: String) {
        runCatching {
            val output = execRoot("ip rule show") ?: return@runCatching
            val priorityLine = output.lineSequence().firstOrNull { line ->
                line.contains("to $cidr") && line.contains("lookup main")
            } ?: return@runCatching
            val priority = priorityLine.substringBefore(':').trim().toIntOrNull() ?: return@runCatching
            execRoot("ip rule del priority $priority")
            installedRules.remove(priority)
            Log.i(TAG, "removeRuleForCidr: removed priority=$priority for $cidr")
        }
    }

    private fun removeAllTunRules() {
        if (installedRules.isEmpty()) return
        runCatching {
            val output = execRoot("ip rule show") ?: return@runCatching
            val priorities = output.lineSequence()
                .filter { line ->
                    val p = line.substringBefore(':').trim().toIntOrNull() ?: return@filter false
                    p in IP_RULE_PRIORITY_START until (IP_RULE_PRIORITY_START + 10000) && line.contains("lookup main")
                }
                .mapNotNull { it.substringBefore(':').trim().toIntOrNull() }
                .toList()
            for (p in priorities) {
                execRoot("ip rule del priority $p")
                Log.i(TAG, "removeAllTunRules: removed priority=$p")
            }
        }
        installedRules.clear()
    }

    companion object {
        private const val TAG = "RootTunRouteManager"
        private const val MAGIC_DNS_ROUTE = "100.100.100.101/32"
        // Priority for our easytier0 ip rules. Android's per-interface rules start
        // at 10000; using 5000 places our TUN rules ahead of them so TUN routes win.
        private const val IP_RULE_PRIORITY_START = 5000
    }
}

package cc.ptoe.easytier.compose.transport.root

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import cc.ptoe.easytier.compose.core.EasyTierJni
import cc.ptoe.easytier.compose.core.networkInfo
import cc.ptoe.easytier.compose.data.RuntimePeer
import cc.ptoe.easytier.compose.data.RuntimeState
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

class EasyTierRootService : RootService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currentStatus = AtomicReference(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null, null, null))
    private var tunDescriptor: ParcelFileDescriptor? = null
    // easytier0 fd captured in the main namespace before unshare; handed to
    // EasyTier via setTunFd after the daemon enters the isolated netns. The fd
    // stays valid across netns boundaries, so EasyTier can keep reading/writing
    // the main-namespace TUN while its own sockets live in the isolated ns.
    private var tunFd: Int = -1
    private var tunDevName: String = "easytier0"
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    // Tracks the startRoot coroutine so stopRoot/failRoot/onDestroy can cancel it.
    // Without this, a slow startRoot (e.g. blocked in runNetworkInstance or DHCP
    // polling) survives cleanupResources() and may call attachTun AFTER stop,
    // re-creating the TUN and routes on an already-stopped daemon.
    private var startJob: Job? = null
    private var magicDnsEnabled = false
    private var savedDns1: String? = null
    private var savedDns2: String? = null
    // Guards cleanup so stopRoot/failRoot/onDestroy are idempotent. Set to true
    // while an EasyTier instance is running or starting; cleared by stopRoot/failRoot.
    @Volatile private var active = false
    // Tracks ip rule priorities we installed so stopRoot/failRoot can remove them.
    // Android's per-interface routing tables (wlan0, rmnet_data*, etc.) are queried
    // via ip rule before the main table, so a connected route in main (e.g.
    // 10.126.126.0/24 dev easytier0) is bypassed for traffic from root processes.
    // We insert high-priority "to <cidr> lookup main" rules to fix this.
    private val installedRules = mutableListOf<Int>()
    // TUN routes we added to the main namespace's easytier0 (via nsenter), so
    // cleanup can remove exactly the set we installed.
    private val installedRoutes = mutableSetOf<String>()

    // --- netns isolation state ---
    // The daemon moves itself into an anonymous network namespace (CLONE_NEWNET)
    // before starting EasyTier, so EasyTier's getifaddrs cannot see mihomo /
    // VpnService TUNs and its SO_BINDTODEVICE candidates are veth/lo only. A veth
    // pair bridges the isolated ns to the main ns; NAT + a from-bridge policy
    // route force traffic out the physical interface, bypassing every other TUN.
    private var inNetns = false
    private var bridgeSetup = false
    private var phyInterface: String? = null
    private var phyGateway: String? = null
    private var savedIpForward: String? = null

    companion object {
        private const val TAG = "EasyTierRootService"
        private const val MAGIC_DNS_FAKE_IP = "100.100.100.101"
        private const val MAGIC_DNS_ROUTE = "100.100.100.101/32"
        // Priority for our easytier0 ip rules. Android's per-interface rules start
        // at 10000; using 5000 places our TUN rules ahead of them so TUN routes win.
        private const val IP_RULE_PRIORITY_START = 5000

        // veth bridge: main-ns end (et_v0) <-> isolated-ns end (et_v1).
        private const val VETH_MAIN = "et_v0"
        private const val VETH_NS = "et_v1"
        private const val VETH_MAIN_IP = "10.200.0.1/30"
        private const val VETH_NS_IP = "10.200.0.2/30"
        private const val VETH_MAIN_GW = "10.200.0.1"
        private const val BRIDGE_CIDR = "10.200.0.0/30"
        // Dedicated routing table for bridge-sourced traffic in the main ns.
        private const val BRIDGE_TABLE = 51820
        private const val BRIDGE_RULE_PRIORITY = 4000
        // PID 1 (init) lives in the main namespace, so "nsenter -t 1 -n" from
        // inside the isolated ns runs commands against the main ns network stack.
        private const val NSENTER_MAIN = "nsenter -t 1 -n"
    }

    private val binder = object : IEasyTierRootService.Stub() {
        override fun start(profileId: String, toml: String, spec: RootTunSpec) {
            startJob?.cancel()
            startJob = scope.launch { startRoot(profileId, toml, spec) }
        }

        override fun stop() {
            scope.launch { stopRoot() }
        }

        override fun getStatus(): RootRuntimeStatus = currentStatus.get()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        // Defensive cleanup for the case where the daemon process is killed
        // without an explicit stop. When stopRoot/failRoot already ran, this is
        // effectively a no-op (tunDescriptor is null, installedRules is empty).
        // Called synchronously (not via scope.launch) so it runs even if the
        // scope is about to be cancelled.
        startJob?.cancel()
        startJob = null
        cleanupResources()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startRoot(profileId: String, toml: String, spec: RootTunSpec) {
        Log.i(TAG, "startRoot: profileId=$profileId ipv4Cidr=${spec.ipv4Cidr} mtu=${spec.mtu} manualRoutes=${spec.manualRoutes} proxyCidrs=${spec.proxyCidrs} magicDns=${spec.magicDns}")
        active = true
        currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, profileId, null, null, null, null))
        runCatching {
            require(EasyTierJni.parseConfig(toml) == 0) { nativeError("EasyTier rejected configuration") }
            cleanupResources()
            magicDnsEnabled = spec.magicDns
            currentStatus.set(RootRuntimeStatus(RuntimeState.STARTING.name, profileId, null, null, null, null))
            // 1) Create easytier0 in the MAIN namespace (before unshare) and keep
            //    its fd. Static IPv4 is configured now; DHCP leaves it unconfigured
            //    until pollDhcp resolves an address.
            createTunInMainNs(spec)
            // 2) Build the veth bridge + NAT + policy route in the main ns so the
            //    soon-to-be-isolated daemon can reach the physical network (and
            //    only the physical network).
            setupNetnsBridge()
            // 3) Move the daemon into a new anonymous netns and bring up the
            //    namespace-side veth + default route.
            enterNetns()
            // 4) Start EasyTier. Every socket it creates now lives in the
            //    isolated ns, so getifaddrs cannot enumerate mihomo/VpnService
            //    TUNs and SO_BINDTODEVICE candidates are veth/lo only.
            require(EasyTierJni.runNetworkInstance(toml) == 0) { nativeError("EasyTier failed to start") }
            if (spec.magicDns) enableMagicDnsSystemDns()
            if (!spec.ipv4Cidr.isNullOrBlank()) {
                Log.i(TAG, "startRoot: using static IPv4 ${spec.ipv4Cidr}")
                attachTun(profileId, spec.ipv4Cidr, spec)
            } else {
                Log.i(TAG, "startRoot: DHCP mode, polling for virtual IPv4")
                pollJob = scope.launch { pollDhcp(profileId, spec) }
            }
        }.onFailure {
            Log.e(TAG, "startRoot failed", it)
            failRoot(it.message ?: nativeError("Root EasyTier start failed"))
        }
    }

    /** Creates easytier0 in the main namespace and retains its fd for setTunFd. */
    private fun createTunInMainNs(spec: RootTunSpec) {
        val devName = spec.devName.ifBlank { "easytier0" }
        tunDevName = devName
        Log.i(TAG, "createTunInMainNs: dev=$devName cidr=${spec.ipv4Cidr} mtu=${spec.mtu}")
        val fd = RootTunNative.create(spec.ipv4Cidr, spec.mtu, devName)
        tunFd = fd
        tunDescriptor = ParcelFileDescriptor.adoptFd(fd)
    }

    /**
     * Probes the main namespace's physical default route, then builds the veth
     * bridge: et_v0 (main ns) <-> et_v1 (to be moved into the isolated ns),
     * ip_forward, NAT MASQUERADE, FORWARD ACCEPT, and a from-bridge policy route
     * pointing at the physical interface so bridge traffic bypasses any
     * mihomo/VpnService 0.0.0.0/0 in the main table.
     */
    private fun setupNetnsBridge() {
        val (gw, phy) = detectPhysicalGateway()
            ?: error("no physical default route found in main namespace; cannot bridge isolated netns")
        phyGateway = gw
        phyInterface = phy
        Log.i(TAG, "setupNetnsBridge: phy=$phy gw=$gw")

        // Clean up any stale veth from a previous run that didn't clean up
        // properly (e.g. daemon killed before cleanupResources ran). Deleting
        // et_v0 also removes its peer; del on a non-existent iface is silenced.
        execRoot("ip link del $VETH_MAIN 2>/dev/null")
        execRoot("ip link del $VETH_NS 2>/dev/null")

        savedIpForward = execRoot("cat /proc/sys/net/ipv4/ip_forward")?.trim()
        val (vethOut, vethErr, vethCode) = execRootVerbose(
            "ip link add $VETH_MAIN type veth peer name $VETH_NS"
        )
        require(vethCode == 0) {
            "failed to create veth pair $VETH_MAIN<->$VETH_NS: ${vethErr.trim()}"
        }
        execRoot("ip addr add $VETH_MAIN_IP dev $VETH_MAIN")
        execRoot("ip link set $VETH_MAIN up")
        execRoot("sysctl -w net.ipv4.ip_forward=1")
        execRoot("iptables -t nat -A POSTROUTING -s $BRIDGE_CIDR -j MASQUERADE")
        execRoot("iptables -I FORWARD -s $BRIDGE_CIDR -j ACCEPT")
        execRoot("iptables -I FORWARD -d $BRIDGE_CIDR -j ACCEPT")
        // Force bridge-sourced traffic through the physical interface, bypassing
        // mihomo/VpnService TUNs that may own 0.0.0.0/0 in the main table.
        execRoot("ip route add table $BRIDGE_TABLE default via $gw dev $phy")
        execRoot("ip rule add from $BRIDGE_CIDR lookup $BRIDGE_TABLE priority $BRIDGE_RULE_PRIORITY")
        bridgeSetup = true
        Log.i(TAG, "setupNetnsBridge: bridge ready")
    }

    /**
     * Finds a physical default route in the main namespace.
     *
     * Android's per-interface routing architecture keeps `default via <gw> dev <phy>`
     * in per-interface tables (e.g. `wlan0`, `rmnet_data0`), not in `main`. The
     * main table is often empty or hijacked by VpnService/mihomo TUN, so
     * `ip route show default` alone is unreliable. We probe in this order:
     *   1. `ip route show default` in the main table (cheap, works when nothing
     *      has overwritten the default).
     *   2. For every physical interface, `ip route show table <iface>` — this is
     *      where Android actually puts the per-interface default route.
     *   3. `ip route show table all` — scan all tables for a `default` route via
     *      a physical interface.
     * For point-to-point links (e.g. mobile data) that expose `default dev <phy>`
     * without a gateway, the interface's own IPv4 address is used as the gateway
     * so we can build a `default via <gw>` route in the bridge table.
     */
    private fun detectPhysicalGateway(): Pair<String, String>? {
        // Strategy 1: main table default route.
        val mainOut = execRoot("ip route show default").orEmpty()
        parseDefaultRouteLine(mainOut)?.let { return it }

        // Strategy 2: probe each physical interface's per-interface table.
        val physIfaces = listPhysicalInterfaces()
        Log.i(TAG, "detectPhysicalGateway: physical interfaces=$physIfaces")
        for (iface in physIfaces) {
            val tableOut = execRoot("ip route show table $iface").orEmpty()
            val hit = parseDefaultRouteLine(tableOut, preferredDev = iface)
            if (hit != null) {
                Log.i(TAG, "detectPhysicalGateway: found in table=$iface: $hit")
                return hit
            }
        }

        // Strategy 3: scan every table.
        val allOut = execRoot("ip route show table all").orEmpty()
        val allHit = parseDefaultRouteLine(allOut, preferPhysical = true)
        if (allHit != null) {
            Log.i(TAG, "detectPhysicalGateway: found via table-all scan: $allHit")
            return allHit
        }

        Log.w(TAG, "detectPhysicalGateway: no physical default route found." +
            " mainOut=${mainOut.replace('\n', '|').take(400)}" +
            " allOut=${allOut.replace('\n', '|').take(400)}")
        return null
    }

    /**
     * Parses a `default [via <gw>] dev <iface>` line. Returns (gw, dev) on match.
     *
     * If [preferredDev] is set, only matches whose egress dev equals it are
     * considered; this is used when scanning a per-interface table. If
     * [preferPhysical] is true, the first physical-interface match wins; only
     * when no physical match exists do we fall back to any default-with-gateway.
     * For `default dev <phy>` without a gateway (P2P/mobile), the interface's
     * IPv4 is used as the gateway.
     */
    private fun parseDefaultRouteLine(
        out: String,
        preferredDev: String? = null,
        preferPhysical: Boolean = false,
    ): Pair<String, String>? {
        val withGw = Regex("""(?:^|\n)\s*default\s+via\s+(\S+)\s+dev\s+(\S+)""")
        val devOnly = Regex("""(?:^|\n)\s*default\s+dev\s+(\S+)""")

        val withGwMatches = withGw.findAll(out).mapNotNull { m ->
            val gw = m.groupValues[1]
            val dev = m.groupValues[2]
            if (preferredDev != null && dev != preferredDev) null
            else gw to dev
        }.toList()

        // Prefer physical matches first.
        val physWithGw = withGwMatches.firstOrNull { (_, dev) -> isPhysicalInterface(dev) }
        if (physWithGw != null) return physWithGw
        if (!preferPhysical && withGwMatches.isNotEmpty()) return withGwMatches.first()

        // Point-to-point default (no via): use the interface's IPv4 as gateway.
        val devOnlyMatches = devOnly.findAll(out).mapNotNull { m ->
            val dev = m.groupValues[1]
            if (preferredDev != null && dev != preferredDev) null
            else dev
        }.toList()
        for (dev in devOnlyMatches) {
            if (!isPhysicalInterface(dev)) continue
            val localIp = getInterfaceIpv4(dev) ?: continue
            return localIp to dev
        }

        return null
    }

    /** Lists physical interface names from /sys/class/net/. */
    private fun listPhysicalInterfaces(): List<String> {
        val out = execRoot("ls /sys/class/net/").orEmpty()
        return out.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && isPhysicalInterface(it) }
    }

    /** Returns the first IPv4 address (without prefix) of [dev], or null. */
    private fun getInterfaceIpv4(dev: String): String? {
        val out = execRoot("ip -o -4 addr show dev $dev").orEmpty()
        val match = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)""").find(out) ?: return null
        return match.groupValues[1]
    }

    private fun isPhysicalInterface(dev: String): Boolean {
        if (dev == "lo") return false
        if (dev.startsWith("tun") || dev.startsWith("utun")) return false
        if (dev.startsWith("easytier") || dev.startsWith("et_v")) return false
        if (dev.startsWith("veth") || dev.startsWith("docker")) return false
        if (dev.startsWith("rmnet_r") || dev.startsWith("rmnet_data")) return true // mobile data
        if (dev.startsWith("wlan") || dev.startsWith("eth") || dev.startsWith("rmnet")) return true
        return false
    }

    /**
     * unshare(CLONE_NEWNET) the daemon, then move et_v1 into the new ns and
     * bring up ns-local networking (et_v1 address, lo up, default via et_v0).
     *
     * Android's toybox `ip link set et_v1 netns <pid>` silently no-ops (toybox
     * treats the argument as a named netns under /var/run/netns/, not a PID,
     * and exits 0 without moving anything). So the primary move path is a JNI
     * that temporarily setns into the main ns and issues an RTM_SETLINK with
     * IFLA_NET_NS_PID directly via netlink, fully bypassing toybox. The toybox
     * forms are kept as best-effort fallbacks for non-Android or future iproute2.
     */
    private fun enterNetns() {
        val rc = RootTunNative.unshareNetwork()
        require(rc == 0) { "unshare(CLONE_NEWNET) failed: rc=$rc" }
        inNetns = true
        Log.i(TAG, "enterNetns: daemon now in isolated netns")

        // Primary path: JNI netlink move. Temporarily setns into main ns,
        // relocate et_v1 by IFLA_NET_NS_PID, setns back. Bypasses toybox.
        val jniRc = RootTunNative.pullInterfaceFromMainNs(VETH_NS)
        Log.i(TAG, "enterNetns: pullInterfaceFromMainNs($VETH_NS) rc=$jniRc")
        if (jniRc == 0 && execRoot("ip link show $VETH_NS").orEmpty().isNotBlank()) {
            configureNsNetworking()
            return
        }

        // Fallbacks: toybox `ip` forms (unlikely to work on Android, but kept
        // for completeness / non-Android root environments).
        val pid = Process.myPid()
        val fallbackCmds = listOf(
            "$NSENTER_MAIN ip link set $VETH_NS netns /proc/$pid/ns/net",
            "$NSENTER_MAIN ip link set $VETH_NS netns $pid",
        )
        for (cmd in fallbackCmds) {
            val (out, err, code) = execRootVerbose(cmd)
            Log.i(TAG, "enterNetns: fallback $cmd -> exit=$code err='${err.trim()}'")
            if (execRoot("ip link show $VETH_NS").orEmpty().isNotBlank()) {
                configureNsNetworking()
                return
            }
        }

        // All strategies failed: gather diagnostics before failing.
        val nsIfaces = execRoot("ip -o link show").orEmpty().replace('\n', '|').take(400)
        val mainHasV1 = execMainNs("ip link show $VETH_NS").orEmpty().replace('\n', '|').take(200)
        error(
            "$VETH_NS not visible in isolated netns after move. " +
                "pullInterfaceFromMainNs rc=$jniRc. " +
                "isolated ns ifaces: [$nsIfaces]. " +
                "main ns still has $VETH_NS: [$mainHasV1]"
        )
    }

    private fun configureNsNetworking() {
        execRoot("ip addr add $VETH_NS_IP dev $VETH_NS")
        execRoot("ip link set $VETH_NS up")
        execRoot("ip link set lo up")
        execRoot("ip route add default via $VETH_MAIN_GW")
        Log.i(TAG, "configureNsNetworking: ns networking up, default via $VETH_MAIN_GW")
    }

    private suspend fun pollDhcp(profileId: String, spec: RootTunSpec) {
        while (currentStatus.get().state == RuntimeState.STARTING.name) {
            runCatching {
                val raw = EasyTierJni.collectNetworkInfos(1)
                val info = raw?.networkInfo(profileId)
                if (info?.error?.takeIf(String::isNotBlank) != null) {
                    Log.e(TAG, "pollDhcp: EasyTier error: ${info.error}")
                    error(info.error)
                }
                val cidr = info?.virtualIpv4
                if (!cidr.isNullOrBlank()) {
                    Log.i(TAG, "pollDhcp: got virtual IPv4 $cidr, routes=${info.routes}")
                    attachTun(profileId, cidr, spec, info.routes)
                }
            }.onFailure {
                Log.e(TAG, "pollDhcp failed", it)
                failRoot(it.message ?: nativeError("Root DHCP polling failed"))
            }
            delay(2_000)
        }
    }

    private fun attachTun(profileId: String, cidr: String, spec: RootTunSpec, runtimeRoutes: List<String> = emptyList()) {
        val devName = tunDevName
        Log.i(TAG, "attachTun: profileId=$profileId cidr=$cidr dev=$devName")
        // DHCP mode: easytier0 had no address at create time; configure it now
        // in the main namespace (the daemon is in the isolated ns, so nsenter).
        if (spec.ipv4Cidr.isNullOrBlank()) {
            execMainNs("ip addr add $cidr dev $devName")
        }
        require(EasyTierJni.setTunFd(profileId, tunFd) == 0) { nativeError("EasyTier failed to attach root TUN") }
        syncTunRoutes(cidr, runtimeRoutes, spec)
        currentStatus.set(RootRuntimeStatus(RuntimeState.RUNNING.name, profileId, cidr, devName, null, null))
        Log.i(TAG, "attachTun: running, virtualIpv4=$cidr dev=$devName")
        statusJob = scope.launch { pollStatus(profileId, cidr, spec) }
    }

    /**
     * Syncs the full TUN route set to the main namespace's easytier0 via nsenter.
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
     */
    private fun syncTunRoutes(cidr: String, runtimeRoutes: List<String>, spec: RootTunSpec) {
        val subnet = virtualIpSubnet(cidr)
        val magicDnsRoute = if (spec.magicDns) listOf(MAGIC_DNS_ROUTE) else emptyList()
        val allRoutes = (listOfNotNull(subnet) + runtimeRoutes + spec.manualRoutes + magicDnsRoute)
            .filter(String::isNotBlank).distinct().sorted()
        Log.i(TAG, "syncTunRoutes: ${allRoutes.size} routes: $allRoutes")
        val devName = tunDevName
        // Remove routes no longer wanted.
        for (r in (installedRoutes - allRoutes).toList()) {
            execMainNs("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
        // Add new routes. "RTNETLINK answers: File exists" means the route is
        // already in the kernel — treat as success.
        for (r in allRoutes - installedRoutes) {
            val out = execMainNs("ip route add $r dev $devName 2>&1").orEmpty()
            if (out.isBlank() || out.contains("File exists")) {
                installedRoutes.add(r)
            } else {
                Log.w(TAG, "syncTunRoutes: add $r failed: ${out.trim()}")
            }
        }
        // Install ip rule entries for each TUN route so Android's per-interface
        // routing tables (wlan0/rmnet_data*) don't intercept traffic destined
        // for the TUN.
        installTunRules(allRoutes)
    }

    /** Computes the network subnet CIDR from an IPv4 CIDR, e.g. "10.126.126.1/24" → "10.126.126.0/24". */
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
     * (ahead of Android's per-interface rules at 10000+) in the MAIN namespace.
     * Idempotent: only adds rules for CIDRs not already installed.
     */
    private fun installTunRules(cidrs: List<String>) {
        val wanted = cidrs.filter(String::isNotBlank).distinct().sorted()
        // Remove rules whose CIDR is no longer wanted.
        val removedCidrs = currentRuleCidrs() - wanted.toSet()
        for (cidr in removedCidrs) removeRuleForCidr(cidr)
        // Add rules for new CIDRs. Each gets a unique priority starting at 5000.
        val existing = currentRuleCidrs()
        var nextPriority = IP_RULE_PRIORITY_START + installedRules.size
        for (cidr in wanted) {
            if (cidr in existing) continue
            val priority = nextPriority++
            runCatching { execMainNs("ip rule add to $cidr lookup main priority $priority") }
                .onFailure { Log.w(TAG, "installTunRules: add rule for $cidr failed: ${it.message}") }
                .onSuccess {
                    installedRules.add(priority)
                    Log.i(TAG, "installTunRules: added rule priority=$priority to $cidr lookup main")
                }
        }
    }

    /** Returns the set of CIDRs that currently have an ip rule installed by us. */
    private fun currentRuleCidrs(): Set<String> = runCatching {
        val output = execMainNs("ip rule show") ?: return@runCatching emptySet()
        output.lineSequence()
            .mapNotNull { line ->
                // Lines look like: "5000:	from all to 10.126.126.0/24 lookup main"
                val toMatch = Regex("""to\s+(\S+)""").find(line)?.groupValues?.getOrNull(1)
                val isMain = line.contains("lookup main")
                if (toMatch != null && isMain) toMatch else null
            }
            .toSet()
    }.getOrDefault(emptySet())

    private fun removeRuleForCidr(cidr: String) {
        // Find the priority for this CIDR from ip rule show, then delete by priority.
        runCatching {
            val output = execMainNs("ip rule show") ?: return@runCatching
            val priorityLine = output.lineSequence().firstOrNull { line ->
                line.contains("to $cidr") && line.contains("lookup main")
            } ?: return@runCatching
            val priority = priorityLine.substringBefore(':').trim().toIntOrNull() ?: return@runCatching
            execMainNs("ip rule del priority $priority")
            installedRules.remove(priority)
            Log.i(TAG, "removeRuleForCidr: removed priority=$priority for $cidr")
        }
    }

    private fun removeAllTunRules() {
        if (installedRules.isEmpty()) return
        // Re-read ip rule show and delete all rules in our priority range that
        // point to main, to be robust against any drift in installedRules state.
        runCatching {
            val output = execMainNs("ip rule show") ?: return@runCatching
            val priorities = output.lineSequence()
                .filter { line ->
                    val p = line.substringBefore(':').trim().toIntOrNull() ?: return@filter false
                    p in IP_RULE_PRIORITY_START until (IP_RULE_PRIORITY_START + 10000) && line.contains("lookup main")
                }
                .mapNotNull { it.substringBefore(':').trim().toIntOrNull() }
                .toList()
            for (p in priorities) {
                execMainNs("ip rule del priority $p")
                Log.i(TAG, "removeAllTunRules: removed priority=$p")
            }
        }
        installedRules.clear()
    }

    /** Runs a command in the daemon's current namespace. */
    private fun execRoot(command: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            Log.w(TAG, "execRoot: '$command' exit=$code err=${error.trim()}")
        }
        output
    }.getOrNull()

    /** Like [execRoot] but returns (stdout, stderr, exitCode) for critical commands. */
    private fun execRootVerbose(command: String): Triple<String, String, Int> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        Triple(output, error, code)
    }.getOrElse { Triple("", it.message ?: it::class.simpleName.orEmpty(), -1) }

    /** Runs a command against the MAIN namespace (nsenter -t 1 -n). Works both
     *  before and after unshare: before unshare the daemon is already in the
     *  main ns so nsenter is a harmless no-op; after unshare it reaches back. */
    private fun execMainNs(command: String): String? = execRoot("$NSENTER_MAIN $command")

    private suspend fun pollStatus(profileId: String, cidr: String, spec: RootTunSpec) {
        var lastRoutes: List<String> = emptyList()
        while (currentStatus.get().state == RuntimeState.RUNNING.name) {
            var shouldFail = false
            var failMessage: String? = null
            runCatching {
                val raw = EasyTierJni.collectNetworkInfos(1)
                val info = raw?.networkInfo(profileId) ?: run {
                    Log.w(TAG, "pollStatus: networkInfo returned null for $profileId")
                    return@runCatching
                }
                if (info.error?.takeIf(String::isNotBlank) != null) {
                    Log.e(TAG, "pollStatus: EasyTier error: ${info.error}")
                    shouldFail = true
                    failMessage = info.error
                    return@runCatching
                }
                val peerIps = info.peers.mapNotNull { it.virtualIpv4 }
                Log.i(TAG, "pollStatus: peers=${info.peers.size} peerIps=$peerIps routes=${info.routes.size} routes=${info.routes}")
                // Continuously refresh TUN routes so newly-connected peers' proxy_cidrs
                // are added and disconnected peers' are removed. Skip the native call
                // when routes have not changed to reduce log noise.
                if (info.routes != lastRoutes) {
                    syncTunRoutes(cidr, info.routes, spec)
                    lastRoutes = info.routes
                }
                val peersJson = if (info.peers.isNotEmpty()) {
                    Json.encodeToString(ListSerializer(RuntimePeer.serializer()), info.peers)
                } else {
                    null
                }
                val status = currentStatus.get()
                currentStatus.set(status.copy(peersJson = peersJson, hostname = info.hostname, natType = info.natType, profileId = profileId))
            }.onFailure {
                Log.e(TAG, "pollStatus failed", it)
            }
            if (shouldFail) {
                failRoot(failMessage ?: "Root EasyTier error")
                return
            }
            delay(2_000)
        }
        Log.w(TAG, "pollStatus: loop exited, state=${currentStatus.get().state}")
    }

    /**
     * Idempotent resource cleanup. Cancels poll jobs, closes the TUN descriptor,
     * releases the EasyTier instance, tears down the easytier0 routes/rules/
     * interface and the veth bridge in the MAIN namespace, and restores system
     * DNS. Safe to call multiple times.
     */
    private fun cleanupResources() {
        pollJob?.cancel()
        pollJob = null
        statusJob?.cancel()
        statusJob = null
        tunDescriptor?.close()
        tunDescriptor = null
        tunFd = -1
        runCatching { EasyTierJni.retainNetworkInstance(null) }
        // All easytier0 + bridge state lives in the MAIN namespace; reach it via
        // nsenter regardless of whether unshare has happened yet.
        cleanupTunRoutesAndRules()
        cleanupNetnsBridge()
        if (magicDnsEnabled) restoreSystemDns()
        magicDnsEnabled = false
        inNetns = false
    }

    /** Removes easytier0 routes, ip rules, and the interface itself from the main ns. */
    private fun cleanupTunRoutesAndRules() {
        removeAllTunRules()
        val devName = tunDevName
        for (r in installedRoutes.toList()) {
            execMainNs("ip route del $r dev $devName 2>/dev/null")
            installedRoutes.remove(r)
        }
        // Delete the easytier0 interface. RootTunNative.destroy() is NOT called:
        // after unshare it would netlink against the isolated ns (where easytier0
        // does not exist) and could DELLINK the wrong ifindex (e.g. et_v1/lo).
        // Deleting via ip link del in the main ns is safe and also releases the
        // retained control fd held inside RootTunNative when the daemon exits.
        execMainNs("ip link del $devName 2>/dev/null")
    }

    /** Tears down the veth bridge, NAT, FORWARD rules, policy route, and ip_forward. */
    private fun cleanupNetnsBridge() {
        if (!bridgeSetup) return
        bridgeSetup = false
        val gw = phyGateway
        val phy = phyInterface
        execMainNs("ip rule del from $BRIDGE_CIDR lookup $BRIDGE_TABLE priority $BRIDGE_RULE_PRIORITY 2>/dev/null")
        if (gw != null && phy != null) {
            execMainNs("ip route del table $BRIDGE_TABLE default via $gw dev $phy 2>/dev/null")
        }
        execMainNs("iptables -t nat -D POSTROUTING -s $BRIDGE_CIDR -j MASQUERADE 2>/dev/null")
        execMainNs("iptables -D FORWARD -s $BRIDGE_CIDR -j ACCEPT 2>/dev/null")
        execMainNs("iptables -D FORWARD -d $BRIDGE_CIDR -j ACCEPT 2>/dev/null")
        // Deleting the main-ns veth end also removes the isolated-ns peer.
        execMainNs("ip link del $VETH_MAIN 2>/dev/null")
        savedIpForward?.let { execMainNs("sysctl -w net.ipv4.ip_forward=$it 2>/dev/null") }
        savedIpForward = null
        phyGateway = null
        phyInterface = null
        Log.i(TAG, "cleanupNetnsBridge: torn down")
    }

    private fun stopRoot() {
        if (!active) return
        active = false
        startJob?.cancel()
        startJob = null
        Log.i(TAG, "stopRoot")
        cleanupResources()
        currentStatus.set(RootRuntimeStatus(RuntimeState.STOPPED.name, null, null, null, null, null))
        // In daemon mode, stopSelf() terminates this daemon service so the root
        // process exits. Without it the daemon would linger after an explicit stop.
        stopSelf()
    }

    private fun failRoot(message: String) {
        if (!active) return
        active = false
        // startRoot is ending itself (via onFailure); clear the reference so a
        // later stopRoot/onDestroy doesn't try to cancel an already-completing job.
        startJob = null
        Log.e(TAG, "failRoot: $message")
        cleanupResources()
        currentStatus.set(RootRuntimeStatus(RuntimeState.ERROR.name, null, null, null, message, null))
        stopSelf()
    }

    private fun enableMagicDnsSystemDns() {
        savedDns1 = readSystemDns("dns1")
        savedDns2 = readSystemDns("dns2")
        Log.i(TAG, "enableMagicDnsSystemDns: saved dns1=$savedDns1 dns2=$savedDns2, setting to $MAGIC_DNS_FAKE_IP")
        writeSystemDns("dns1", MAGIC_DNS_FAKE_IP)
        writeSystemDns("dns2", MAGIC_DNS_FAKE_IP)
    }

    private fun restoreSystemDns() {
        Log.i(TAG, "restoreSystemDns: dns1=$savedDns1 dns2=$savedDns2")
        val d1 = savedDns1
        val d2 = savedDns2
        if (d1 != null) writeSystemDns("dns1", d1) else clearSystemDns("dns1")
        if (d2 != null) writeSystemDns("dns2", d2) else clearSystemDns("dns2")
        savedDns1 = null
        savedDns2 = null
    }

    private fun readSystemDns(key: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("settings", "get", "global", key))
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.takeIf { it.isNotEmpty() && it != "null" }
    }.getOrNull()

    private fun writeSystemDns(key: String, value: String) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("settings", "put", "global", key, value))
            process.waitFor()
        }
    }

    private fun clearSystemDns(key: String) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("settings", "delete", "global", key))
            process.waitFor()
        }
    }

    private fun nativeError(fallback: String) = EasyTierJni.getLastError() ?: fallback
}

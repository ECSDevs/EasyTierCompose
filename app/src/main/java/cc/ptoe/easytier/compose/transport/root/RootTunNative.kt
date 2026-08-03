package cc.ptoe.easytier.compose.transport.root

/** Privileged native creator for the root-process-only TUN interface. */
object RootTunNative {
    init {
        System.loadLibrary("root_tun_jni")
    }

    external fun create(ipv4Cidr: String?, mtu: Int, devName: String): Int
    external fun syncRoutes(routes: Array<String>)
    external fun destroy()
    /** Moves the calling process into a new anonymous network namespace. */
    external fun unshareNetwork(): Int

    /**
     * Pulls interface [iface] from the main network namespace (PID 1's netns)
     * into the caller's current network namespace. Uses RTM_SETLINK with
     * IFLA_NET_NS_PID via netlink, bypassing Android's toybox `ip` which
     * silently no-ops on `netns <pid>`.
     *
     * Must be called after [unshareNetwork]: the function temporarily setns
     * into the main ns to reach the interface, moves it into the caller's
     * (isolated) ns, then setns back.
     *
     * @return 0 on success, or a negative errno on failure.
     */
    external fun pullInterfaceFromMainNs(iface: String): Int
}

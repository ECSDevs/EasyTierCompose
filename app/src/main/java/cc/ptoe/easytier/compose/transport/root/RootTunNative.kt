package cc.ptoe.easytier.compose.transport.root

/** Privileged native creator for the root-process-only easytier0 TUN interface. */
object RootTunNative {
    init {
        System.loadLibrary("root_tun_jni")
    }

    external fun create(ipv4Cidr: String?, mtu: Int): Int
    external fun syncRoutes(routes: Array<String>)
    external fun destroy()
}

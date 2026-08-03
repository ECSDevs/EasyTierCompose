package cc.ptoe.easytier.compose.transport.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cc.ptoe.easytier.compose.MainActivity
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.EasyTierJni

class EasyTierVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val command = intent ?: return stopAndExit(startId)
        val instanceName = command.getStringExtra(EXTRA_INSTANCE_NAME) ?: return stopAndExit(startId, "missing instance name")
        val ipv4Cidr = command.getStringExtra(EXTRA_IPV4_CIDR) ?: return stopAndExit(startId, "missing ipv4 cidr")
        val routes = command.getStringArrayExtra(EXTRA_ROUTES)?.map { it }.orEmpty().toTypedArray()
        val dns = command.getBooleanExtra(EXTRA_DNS, false)
        val mtu = command.getIntExtra(EXTRA_MTU, 1380)
        Log.i(TAG, "onStartCommand: instance=$instanceName cidr=$ipv4Cidr routes=${routes.size} dns=$dns mtu=$mtu")
        vpnInterface?.close()
        vpnInterface = try {
            createInterface(ipv4Cidr, routes, dns, mtu)
        } catch (e: Exception) {
            Log.e(TAG, "createInterface failed: ${e.message}", e)
            return stopAndExit(startId, "createInterface: ${e.message}")
        }
        val result = EasyTierJni.setTunFd(instanceName, requireNotNull(vpnInterface).fd)
        if (result != 0) {
            Log.e(TAG, "setTunFd failed: result=$result")
            vpnInterface?.close()
            vpnInterface = null
            return stopAndExit(startId, "setTunFd=$result")
        }
        Log.i(TAG, "onStartCommand: success, fd=${vpnInterface?.fd}")
        return Service.START_NOT_STICKY
    }

    private fun stopAndExit(startId: Int, reason: String = "unknown"): Int {
        Log.w(TAG, "stopAndExit: $reason")
        stopSelf(startId)
        return Service.START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    override fun onRevoke() {
        closeTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    private fun createInterface(ipv4Cidr: String, routes: Array<String>, dns: Boolean, mtu: Int): ParcelFileDescriptor {
        val (address, prefix) = ipv4Cidr.parseCidr()
        val builder = Builder()
            .setSession("EasyTier")
            .setBlocking(false)
            .setMtu(mtu)
            .addAddress(address, prefix)
            .addAddress("fd00::1", 128)
        // Add routes defensively: a single malformed/unexpected CIDR (e.g. bare IP
        // that slipped through normalization, or a prefix the device rejects) must
        // not abort the whole VPN establishment — that would leave the user with a
        // connected EasyTier but no TUN interface, manifesting as silent timeouts.
        var added = 0
        for (route in routes) {
            try {
                val (routeAddress, routePrefix) = route.parseCidr()
                builder.addRoute(routeAddress, routePrefix)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "createInterface: skipping invalid route '$route': ${e.message}")
            }
        }
        if (dns) builder.addDnsServer(MAGIC_DNS)
        builder.addDisallowedApplication(packageName)
        Log.i(TAG, "createInterface: cidr=$ipv4Cidr mtu=$mtu routes=${routes.size} added=$added dns=$dns")
        return builder.establish() ?: error("Failed to establish EasyTier VPN")
    }

    private fun closeTunnel() {
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("EasyTier VPN active")
        .setContentText("EasyTier is routing through Android VPN")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "EasyTier VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun String.parseCidr(): Pair<String, Int> {
        val values = split('/', limit = 2)
        require(values.size == 2) { "Invalid CIDR: $this" }
        return values[0] to values[1].toInt()
    }

    companion object {
        private const val TAG = "EasyTierVpnService"
        const val EXTRA_INSTANCE_NAME = "cc.ptoe.easytier.compose.INSTANCE_NAME"
        const val EXTRA_IPV4_CIDR = "cc.ptoe.easytier.compose.IPV4_CIDR"
        const val EXTRA_ROUTES = "cc.ptoe.easytier.compose.ROUTES"
        const val EXTRA_DNS = "cc.ptoe.easytier.compose.DNS"
        const val EXTRA_MTU = "cc.ptoe.easytier.compose.MTU"
        private const val CHANNEL_ID = "easytier_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val MAGIC_DNS = "100.100.100.101"

        fun intent(context: Context, instanceName: String, ipv4Cidr: String, routes: List<String>, dns: Boolean, mtu: Int) =
            Intent(context, EasyTierVpnService::class.java)
                .putExtra(EXTRA_INSTANCE_NAME, instanceName)
                .putExtra(EXTRA_IPV4_CIDR, ipv4Cidr)
                .putExtra(EXTRA_ROUTES, routes.toTypedArray())
                .putExtra(EXTRA_DNS, dns)
                .putExtra(EXTRA_MTU, mtu)
    }
}

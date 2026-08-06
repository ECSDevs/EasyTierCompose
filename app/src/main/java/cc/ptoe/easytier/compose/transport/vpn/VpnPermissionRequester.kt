package cc.ptoe.easytier.compose.transport.vpn

import android.content.Intent

/**
 * Requests the system VPN permission [Intent] returned by [android.net.VpnService.prepare].
 *
 * - Foreground: implemented by [cc.ptoe.easytier.compose.MainActivity] via
 *   `ActivityResultContracts.StartActivityForResult`, returning `true` when the
 *   user accepts the system VPN consent dialog.
 * - Background (e.g. boot): implemented as a no-op returning `false`, because
 *   Android's BAL (Background Activity Launch) enforcement blocks starting the
 *   permission Intent from a non-foreground context. The caller then surfaces
 *   an ERROR, and the user must open the app once to grant permission; on
 *   subsequent boots `VpnService.prepare` returns `null` (already authorized),
 *   so the requester is never invoked.
 */
fun interface VpnPermissionRequester {
    suspend fun request(intent: Intent): Boolean
}

package cc.ptoe.easytier.compose.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.data.mergeInto
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the EasyTier VPN / Root TUN service after system boot when the user
 * has enabled "Start on boot" in Settings.
 *
 * Does NOT launch MainActivity: the selected profile is loaded from DataStore
 * and the coordinator is started headless. This is BAL-compliant because no
 * Activity is started from a RECEIVER process state.
 *
 * - ROOT_TUN: the libsu daemon process runs independently and survives app
 *   process death. The boot receiver only needs to trigger `start` and let
 *   the daemon take over.
 * - VPN_SERVICE: if `VpnService.prepare` returns null (user has previously
 *   authorized VPN), the EasyTier core starts and `EasyTierVpnService` FGS
 *   is started once the virtual IPv4 resolves, keeping the app process alive.
 *   If VPN permission has not been granted, the no-op requester returns false
 *   and the coordinator surfaces an ERROR — the user must open the app once
 *   to grant permission; subsequent boots will be authorized.
 * - no_tun: only the EasyTier core runs in the app process. There is no FGS
 *   to keep the process alive, so the system may reclaim it under memory
 *   pressure. This is a known limitation of headless no_tun mode.
 *
 * Does not auto-connect in the UI sense — the user has explicitly opted in via
 * the "Start on boot" setting.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val repo = ProfileRepository(appContext)
                val profiles = repo.profiles.first()
                if (profiles.isEmpty()) return@launch
                val selectedId = repo.selectedProfileId.first()
                val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()

                // startOnBoot is a per-profile flag toggled from the main Settings
                // page; the selected profile's value decides whether to start at boot.
                val settings = GlobalSettingsRepository(appContext).settings.first()
                if (!settings.mergeInto(profile).startOnBoot) return@launch

                // Share the process-wide singleton coordinator with MainActivity: if the
                // boot receiver created its own instance, a later disconnect() from the
                // activity would not stop this one's poll loop / VPN service.
                val coordinator = EasyTierRuntimeCoordinator.getInstance(appContext, NO_OP_REQUESTER)
                // startDetached launches `start` in the coordinator's own SupervisorJob
                // scope, so this withTimeoutOrNull only abandons *waiting* for the
                // result — the start operation itself (bind + remote.start + daemon
                // startup) keeps running. This is critical for ROOT_TUN where boot-time
                // root process startup can exceed 8s; cancelling bind() mid-way would
                // leave the daemon bound but never told which profile to run.
                val startJob = coordinator.startDetached(profile, settings)
                withTimeoutOrNull(8_000) { startJob.join() }
                    ?: Log.w(TAG, "coordinator.start did not settle within 8s; leaving it running")
            } catch (e: Throwable) {
                Log.e(TAG, "boot start failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"

        // No-op requester: always denies. From a background context BAL blocks
        // launching the system VPN consent Intent, so if `VpnService.prepare`
        // returns non-null (not yet authorized) we cannot prompt the user and
        // must surface ERROR. Once the user grants VPN permission in-app, prepare
        // returns null on subsequent boots and this requester is never invoked.
        private val NO_OP_REQUESTER = VpnPermissionRequester { false }
    }
}

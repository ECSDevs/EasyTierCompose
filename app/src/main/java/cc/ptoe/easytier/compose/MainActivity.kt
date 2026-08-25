package cc.ptoe.easytier.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import cc.ptoe.easytier.compose.ui.EasyTierApp
import cc.ptoe.easytier.compose.ui.EasyTierViewModel
import cc.ptoe.easytier.compose.ui.theme.EasyTierTheme
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: EasyTierViewModel

    // Bridges the ActivityResultContracts callback (which cannot be suspended) to the
    // suspend VpnPermissionRequester. At most one VPN permission request is in flight
    // at a time — the coordinator mutex-serializes start(), so no concurrent requests.
    @Volatile private var pendingPermissionResult: CompletableDeferred<Boolean>? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingPermissionResult?.complete(result.resultCode == RESULT_OK)
        pendingPermissionResult = null
    }

    private val vpnPermissionRequester = VpnPermissionRequester { intent ->
        CompletableDeferred<Boolean>().also { pendingPermissionResult = it }.let { deferred ->
            vpnPermission.launch(intent)
            deferred.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this, Factory(this, vpnPermissionRequester))[EasyTierViewModel::class.java]
        setContent {
            EasyTierTheme {
                EasyTierApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Deliberately does not auto-connect: consent/root flow must be explicit.
    }

    private class Factory(
        private val activity: MainActivity,
        private val permissionRequester: VpnPermissionRequester,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EasyTierViewModel(
            ProfileRepository(activity.applicationContext),
            // Process-wide singleton: if BootCompletedReceiver already created the
            // coordinator (auto-start), reuse it and swap in the activity-backed
            // permission requester so a foreground connect() can prompt for consent.
            EasyTierRuntimeCoordinator.getInstance(activity.applicationContext, permissionRequester),
            GlobalSettingsRepository(activity.applicationContext),
        ) as T
    }
}

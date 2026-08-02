package cc.ptoe.easytier.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.ui.EasyTierApp
import cc.ptoe.easytier.compose.ui.EasyTierViewModel
import cc.ptoe.easytier.compose.ui.theme.EasyTierTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: EasyTierViewModel
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this, Factory(this))[EasyTierViewModel::class.java]
        setContent {
            EasyTierTheme {
                EasyTierApp(
                    viewModel = viewModel,
                    requestVpnPermission = vpnPermission::launch,
                )
            }
        }
    }

    private class Factory(private val activity: MainActivity) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EasyTierViewModel(
            ProfileRepository(activity.applicationContext),
            EasyTierRuntimeCoordinator(activity.applicationContext, activity),
        ) as T
    }

    override fun onResume() {
        super.onResume()
        // Deliberately does not auto-connect: consent/root flow must be explicit.
    }
}

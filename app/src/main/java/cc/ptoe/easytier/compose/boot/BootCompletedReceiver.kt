package cc.ptoe.easytier.compose.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cc.ptoe.easytier.compose.MainActivity
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the app's MainActivity after system boot when the user has enabled
 * "Start on boot" in Settings. Does not auto-connect — the consent / root flow
 * must be triggered explicitly by the user, per project convention.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = GlobalSettingsRepository(context.applicationContext).settings.first()
                if (settings.startOnBoot) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(launchIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

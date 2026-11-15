package dev.brantley.agentbridge.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.brantley.agentbridge.BridgeApp

/** Handles the Reply and Mark-as-read actions Android Auto fires on a car notification. */
class CarActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        val bot = intent.getStringExtra(CarNotifier.EXTRA_BOT)?.takeIf { it.isNotBlank() } ?: return
        when (intent.action) {
            CarNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CarNotifier.KEY_TEXT)?.toString().orEmpty()
                app.car.say(bot, text)
                CarChatService.ensureRunning(context)
            }
            CarNotifier.ACTION_READ -> app.car.markRead(bot)
        }
    }
}

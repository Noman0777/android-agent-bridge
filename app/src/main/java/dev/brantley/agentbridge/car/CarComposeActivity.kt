package dev.brantley.agentbridge.car

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import dev.brantley.agentbridge.BridgeApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Entry point for starting a car conversation: Assistant App Actions
 * (actions.intent.CREATE_MESSAGE), conversation shortcuts, and the share sheet.
 * It sends right away only when the system says the Google assistant or Android Auto
 * launched it. Any other caller gets an on-screen confirm, so another app cannot
 * silently put words in the driver's mouth.
 */
class CarComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BridgeApp
        val settings = runBlocking { app.settings.settings.first() }
        if (!settings.carChatEnabled) {
            Toast.makeText(this, "Turn on Car chat in Agent Bridge settings first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val bots = CarBots.parse(settings.carBots)
        app.car.bots = bots
        val spoken =
            intent.getStringExtra(EXTRA_BOT)
                ?: intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)?.let { id -> bots.firstOrNull { CarBots.shortcutId(it) == id } }
        val bot = CarBots.match(bots, spoken) ?: bots.first()
        val text = (intent.getStringExtra(EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_TEXT)).orEmpty().trim()
        CarChatService.ensureRunning(this)
        if (text.isEmpty()) {
            app.car.prompt(bot)
            finish()
            return
        }
        if (trustedLaunch()) {
            app.car.say(bot, text)
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send to $bot?")
            .setMessage(text.take(500))
            .setPositiveButton("Send") { _, _ ->
                app.car.say(bot, text)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun trustedLaunch(): Boolean {
        // A caller can forge EXTRA_REFERRER; only trust the system-reported referrer.
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) return false
        val host = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return false
        return host in TRUSTED_CALLERS || host == packageName
    }

    companion object {
        const val EXTRA_BOT = "bot"
        const val EXTRA_TEXT = "text"
        val TRUSTED_CALLERS =
            setOf(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.googleassistant",
                "com.google.android.apps.bard",
                "com.google.android.projection.gearhead",
            )
    }
}

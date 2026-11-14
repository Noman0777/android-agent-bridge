package dev.brantley.agentbridge.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.brantley.agentbridge.BridgeApp
import dev.brantley.agentbridge.R
import dev.brantley.agentbridge.net.RelaySocket
import dev.brantley.agentbridge.net.SocketStatus
import dev.brantley.agentbridge.net.carWebSocketUrl
import dev.brantley.agentbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the car chat socket open while Car chat is on. It uses the paired device
 * token on its own relay path (/car), separate from the armed phone-control socket,
 * and it never touches PhoneCore. Arming is still what allows phone control.
 */
class CarChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var socket: RelaySocket? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { (application as BridgeApp).settings.update { it.copy(carChatEnabled = false) } }
            shutdown()
            return START_NOT_STICKY
        }
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        if (!running) {
            running = true
            scope.launch { open() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        socket?.disconnect()
        (application as BridgeApp).car.detach()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun open() {
        val app = application as BridgeApp
        val settings = app.settings.settings.first()
        val token = app.tokens.get().orEmpty()
        if (!settings.carChatEnabled || settings.relayUrl.isBlank() || settings.room.isBlank() || token.isBlank()) {
            shutdown()
            return
        }
        val bots = CarBots.parse(settings.carBots)
        app.car.bots = bots
        app.car.notifier.ensureChannel()
        runCatching { app.car.notifier.publishShortcuts(bots) }
        bots.forEach { app.car.prompt(it) }
        val relay = RelaySocket(scope, isArmed = { running })
        socket = relay
        app.car.attach { frame -> relay.send(frame) }
        relay.onStatus = { status -> if (status == SocketStatus.Connected) app.car.onConnected() }
        relay.onFrame = { frame -> app.car.onFrame(frame) }
        relay.connect(carWebSocketUrl(settings.relayUrl, settings.room), token)
    }

    private fun shutdown() {
        running = false
        socket?.disconnect()
        socket = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(SERVICE_CHANNEL_ID, getString(R.string.car_service_channel), NotificationManager.IMPORTANCE_MIN)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CarChatService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.car_service_title))
            .setContentText(getString(R.string.car_service_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.car_service_stop), stop)
            .build()
    }

    companion object {
        private const val SERVICE_CHANNEL_ID = "agent_bridge_car_service"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "dev.brantley.agentbridge.car.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CarChatService::class.java))
        }

        /** Best effort: restart the socket if the system killed it. */
        fun ensureRunning(context: Context) {
            runCatching { start(context) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CarChatService::class.java).setAction(ACTION_STOP))
        }

        /** Restart so a changed bot list takes effect. */
        fun restart(context: Context) {
            context.stopService(Intent(context, CarChatService::class.java))
            start(context)
        }
    }
}

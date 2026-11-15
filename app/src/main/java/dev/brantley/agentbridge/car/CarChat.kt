package dev.brantley.agentbridge.car

import android.content.Context
import dev.brantley.agentbridge.R
import dev.brantley.agentbridge.net.BridgeLog
import java.util.UUID

/**
 * Owns car chat state: per-bot history, the outgoing queue, and the live socket.
 * Car messages only carry text. They never call PhoneCore or the RPC handler.
 */
class CarChat(
    val notifier: CarPoster,
    private val standingText: (String) -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        CarNotifier(context),
        { bot -> context.getString(R.string.car_standing, bot) },
    )

    val history = CarHistory()
    private val pending = ArrayDeque<String>()
    private val lock = Any()
    private val driveLock = Any()
    private val prompted = HashSet<String>()
    private var sender: ((String) -> Boolean)? = null
    private var inCar = false
    private var postsAllowed = true

    @Volatile
    var bots: List<String> = listOf(CarBots.DEFAULT)

    /** The driver said something to [bot]. Shows it in the conversation and sends it. */
    fun say(bot: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            showCurrent(bot, alert = false)
            return
        }
        history.add(bot, CarLine(fromMe = true, text = clean, at = clock()))
        showCurrent(bot, alert = false)
        val frame = CarFrames.say(UUID.randomUUID().toString(), bot, clean)
        val sent = synchronized(lock) { sender?.invoke(frame) == true }
        if (!sent) {
            synchronized(lock) {
                pending.addLast(frame)
                while (pending.size > 20) pending.removeFirst()
            }
            BridgeLog.d(TAG, "car.say queued until the car socket connects")
        }
    }

    /** Shows the conversation for [bot] so the driver can tap Reply in the car. */
    fun prompt(bot: String) {
        showCurrent(bot, alert = false)
    }

    fun markRead(bot: String) {
        history.markRead(bot)
        showCurrent(bot, alert = false)
    }

    fun onCarConnection(type: Int) {
        val show = ArrayList<String>()
        val cancel = ArrayList<String>()
        synchronized(driveLock) {
            if (!postsAllowed) return
            if (isConnectedCar(type)) {
                if (inCar) return
                inCar = true
                bots.forEach { bot ->
                    if (history.unread(bot) == 0 && prompted.add(bot.lowercase())) show.add(bot)
                }
            } else if (inCar || prompted.isNotEmpty()) {
                inCar = false
                prompted.clear()
                bots.filter { history.unread(it) == 0 }.forEach { cancel.add(it) }
            }
        }
        show.forEach { showCurrent(it, alert = false) }
        cancel.forEach { notifier.cancel(it) }
    }

    fun allowPosts() {
        synchronized(driveLock) { postsAllowed = true }
    }

    fun clearDrive() {
        synchronized(driveLock) {
            inCar = false
            prompted.clear()
        }
    }

    fun onServiceStopped() {
        synchronized(driveLock) {
            postsAllowed = false
            inCar = false
            prompted.clear()
        }
        notifier.cancelAll(bots)
    }

    fun onSettingsDisabled(configured: List<String>) {
        synchronized(driveLock) {
            postsAllowed = false
            inCar = false
            prompted.clear()
        }
        notifier.cancelAll(configured)
    }

    fun attach(send: (String) -> Boolean) {
        synchronized(lock) { sender = send }
    }

    fun detach() {
        synchronized(lock) { sender = null }
    }

    fun onConnected() {
        val toSend: List<String>
        synchronized(lock) {
            val current = sender ?: return
            current(CarFrames.sync())
            toSend = pending.toList()
            pending.clear()
            val failed = toSend.filterNot { current(it) }
            failed.forEach { pending.addLast(it) }
        }
    }

    fun onFrame(raw: String) {
        when (val frame = CarFrames.parse(raw)) {
            is CarInbound.Reply -> {
                val bot = CarBots.match(bots, frame.bot) ?: frame.bot
                history.add(bot, CarLine(fromMe = false, text = frame.text, at = clock()))
                showCurrent(bot, alert = true)
            }
            is CarInbound.Status ->
                if (frame.webhook == "failed") {
                    BridgeLog.e(TAG, "car webhook failed status=${frame.status}")
                    val bot = bots.first()
                    history.add(bot, CarLine(false, "I couldn't reach your agent. The message is saved on the relay.", clock()))
                    showCurrent(bot, alert = true)
                }
            is CarInbound.Error -> BridgeLog.e(TAG, "car frame rejected: ${frame.error}")
            is CarInbound.Ack, null -> Unit
        }
    }

    private fun showCurrent(bot: String, alert: Boolean) {
        val standing: String?
        val lines: List<CarLine>
        val unread: Int
        synchronized(driveLock) {
            if (!postsAllowed) return
            lines = history.lines(bot)
            unread = history.unread(bot)
            standing = if (inCar && unread == 0) standingText(bot) else null
        }
        notifier.show(bot, lines, unread, alert, standing)
    }

    companion object {
        private const val TAG = "CarChat"
    }
}

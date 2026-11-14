package dev.brantley.agentbridge.car

import android.content.Context
import dev.brantley.agentbridge.net.BridgeLog
import java.util.UUID

/**
 * Owns car chat state: per-bot history, the outgoing queue, and the live socket.
 * Car messages only carry text. They never call PhoneCore or the RPC handler.
 */
class CarChat(private val context: Context) {
    val history = CarHistory()
    val notifier = CarNotifier(context)
    private val pending = ArrayDeque<String>()
    private val lock = Any()
    private var sender: ((String) -> Boolean)? = null

    @Volatile
    var bots: List<String> = listOf(CarBots.DEFAULT)

    /** The driver said something to [bot]. Shows it in the conversation and sends it. */
    fun say(bot: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            notifier.show(bot, history.lines(bot), history.unread(bot), alert = false)
            return
        }
        history.add(bot, CarLine(fromMe = true, text = clean, at = System.currentTimeMillis()))
        notifier.show(bot, history.lines(bot), history.unread(bot), alert = false)
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
        notifier.show(bot, history.lines(bot), history.unread(bot), alert = false)
    }

    fun markRead(bot: String) {
        history.markRead(bot)
        notifier.show(bot, history.lines(bot), 0, alert = false)
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
                history.add(bot, CarLine(fromMe = false, text = frame.text, at = System.currentTimeMillis()))
                notifier.show(bot, history.lines(bot), history.unread(bot), alert = true)
            }
            is CarInbound.Status ->
                if (frame.webhook == "failed") {
                    BridgeLog.e(TAG, "car webhook failed status=${frame.status}")
                    val bot = bots.first()
                    history.add(bot, CarLine(false, "I couldn't reach your agent. The message is saved on the relay.", System.currentTimeMillis()))
                    notifier.show(bot, history.lines(bot), history.unread(bot), alert = true)
                }
            is CarInbound.Error -> BridgeLog.e(TAG, "car frame rejected: ${frame.error}")
            is CarInbound.Ack, null -> Unit
        }
    }

    companion object {
        private const val TAG = "CarChat"
    }
}

package dev.brantley.agentbridge.car

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Car chat carries text between the driver and named agents through Android Auto
 * messaging notifications. A car message is never parsed as a phone RPC; agents
 * that want to use the phone still go through the armed session and its safety gates.
 */
object CarBots {
    const val MAX_BOTS = 10
    const val MAX_NAME = 64
    const val DEFAULT = "Grok Bot"

    /** Parses the settings text (one name per line or comma-separated). Never empty. */
    fun parse(raw: String): List<String> {
        val seen = LinkedHashMap<String, String>()
        raw.split('\n', ',')
            .map { it.replace(Regex("[\\p{Cntrl}]"), "").trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_NAME }
            .forEach { name -> seen.putIfAbsent(name.lowercase(), name) }
        val names = seen.values.take(MAX_BOTS)
        return names.ifEmpty { listOf(DEFAULT) }
    }

    /** Stable id for shortcuts and notifications. */
    fun shortcutId(bot: String): String = "car-" + bot.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun notificationId(bot: String): Int = 5000 + (bot.lowercase().hashCode() and 0x0FFF)

    /** Finds the configured bot a spoken or typed name refers to, ignoring case and a trailing "bot". */
    fun match(bots: List<String>, spoken: String?): String? {
        val wanted = normalize(spoken ?: return null)
        if (wanted.isEmpty()) return null
        return bots.firstOrNull { normalize(it) == wanted }
            ?: bots.firstOrNull { normalize(it).removeSuffix(" bot") == wanted.removeSuffix(" bot") }
    }

    private fun normalize(name: String): String = name.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
}

sealed class CarInbound {
    data class Reply(val bot: String, val text: String, val replyTo: String?) : CarInbound()

    data class Ack(val id: String, val webhook: String) : CarInbound()

    data class Status(val id: String, val webhook: String, val status: Int) : CarInbound()

    data class Error(val error: String) : CarInbound()
}

object CarFrames {
    const val MAX_TEXT = 2000
    private val json = Json { ignoreUnknownKeys = true }

    fun say(id: String, bot: String, text: String): String =
        buildJsonObject {
            put("type", "car.say")
            put("id", id)
            put("bot", bot.take(CarBots.MAX_NAME))
            put("text", text.trim().take(MAX_TEXT))
        }.toString()

    fun sync(): String = buildJsonObject { put("type", "car.sync") }.toString()

    fun parse(raw: String): CarInbound? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        return when (str("type")) {
            "car.reply" -> {
                val bot = str("bot")?.trim().orEmpty()
                val text = str("text")?.trim().orEmpty()
                if (bot.isEmpty() || text.isEmpty()) null else CarInbound.Reply(bot.take(CarBots.MAX_NAME), text.take(MAX_TEXT), str("replyTo"))
            }
            "car.ack" -> CarInbound.Ack(str("id").orEmpty(), str("webhook").orEmpty())
            "car.status" -> CarInbound.Status(str("id").orEmpty(), str("webhook").orEmpty(), str("status")?.toIntOrNull() ?: 0)
            "car.error" -> CarInbound.Error(str("error").orEmpty())
            else -> null
        }
    }
}

data class CarLine(val fromMe: Boolean, val text: String, val at: Long)

/** Recent lines per bot, used to rebuild the MessagingStyle notification. */
class CarHistory(private val keep: Int = 8) {
    private val lines = LinkedHashMap<String, ArrayDeque<CarLine>>()
    private val unread = HashMap<String, Int>()

    @Synchronized
    fun add(bot: String, line: CarLine) {
        val queue = lines.getOrPut(bot.lowercase()) { ArrayDeque() }
        queue.addLast(line)
        while (queue.size > keep) queue.removeFirst()
        unread[bot.lowercase()] = if (line.fromMe) 0 else (unread[bot.lowercase()] ?: 0) + 1
    }

    @Synchronized
    fun lines(bot: String): List<CarLine> = lines[bot.lowercase()]?.toList().orEmpty()

    @Synchronized
    fun unread(bot: String): Int = unread[bot.lowercase()] ?: 0

    @Synchronized
    fun markRead(bot: String) {
        unread[bot.lowercase()] = 0
    }
}

package dev.brantley.agentbridge

import dev.brantley.agentbridge.car.CarBots
import dev.brantley.agentbridge.car.CarFrames
import dev.brantley.agentbridge.car.CarHistory
import dev.brantley.agentbridge.car.CarInbound
import dev.brantley.agentbridge.car.CarLine
import dev.brantley.agentbridge.net.carWebSocketUrl
import dev.brantley.agentbridge.net.deviceWebSocketUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarModelTest {
    @Test
    fun botListParsesDedupesAndNeverGoesEmpty() {
        assertEquals(listOf("Grok Bot"), CarBots.parse(""))
        assertEquals(listOf("Grok Bot"), CarBots.parse("  \n , "))
        assertEquals(listOf("Travel Bot", "Work Email Bot"), CarBots.parse("Travel Bot\nWork Email Bot, work email bot\ntravel bot"))
        assertEquals(CarBots.MAX_BOTS, CarBots.parse((1..20).joinToString("\n") { "Bot $it" }).size)
        assertTrue(CarBots.parse("x".repeat(65) + "\nOk").contains("Ok"))
        assertEquals(1, CarBots.parse("x".repeat(65) + "\nOk").size)
    }

    @Test
    fun spokenNamesMatchConfiguredBots() {
        val bots = listOf("Travel Bot", "Sauron Bot")
        assertEquals("Travel Bot", CarBots.match(bots, "travel bot"))
        assertEquals("Travel Bot", CarBots.match(bots, "Travel"))
        assertEquals("Sauron Bot", CarBots.match(bots, "sauron  bot."))
        assertNull(CarBots.match(bots, "Grocery Bot"))
        assertNull(CarBots.match(bots, null))
        assertEquals("car-travel-bot", CarBots.shortcutId("Travel Bot"))
    }

    @Test
    fun sayFrameMatchesTheRelayProtocol() {
        val frame = Json.parseToJsonElement(CarFrames.say("m1", "Travel Bot", "  find a hotel  ")).jsonObject
        assertEquals("car.say", frame["type"]!!.jsonPrimitive.content)
        assertEquals("m1", frame["id"]!!.jsonPrimitive.content)
        assertEquals("Travel Bot", frame["bot"]!!.jsonPrimitive.content)
        assertEquals("find a hotel", frame["text"]!!.jsonPrimitive.content)
        assertEquals(CarFrames.MAX_TEXT, Json.parseToJsonElement(CarFrames.say("m", "b", "x".repeat(5000))).jsonObject["text"]!!.jsonPrimitive.content.length)
        assertEquals("""{"type":"car.sync"}""", CarFrames.sync())
    }

    @Test
    fun inboundFramesParseAndRpcFramesAreIgnored() {
        assertEquals(
            CarInbound.Reply("Travel Bot", "Booked.", "m1"),
            CarFrames.parse("""{"type":"car.reply","bot":"Travel Bot","text":"Booked.","replyTo":"m1","at":"x"}"""),
        )
        assertEquals(CarInbound.Status("m1", "failed", 502), CarFrames.parse("""{"type":"car.status","id":"m1","webhook":"failed","status":502}"""))
        assertNull(CarFrames.parse("""{"type":"car.reply","bot":"","text":"x"}"""))
        // A phone RPC frame is never a car message.
        assertNull(CarFrames.parse("""{"id":"1","method":"tap","params":{"x":1,"y":2}}"""))
        assertNull(CarFrames.parse("not json"))
    }

    @Test
    fun historyKeepsRecentLinesAndCountsUnread() {
        val history = CarHistory(keep = 3)
        (1..5).forEach { history.add("Travel Bot", CarLine(fromMe = false, text = "m$it", at = it.toLong())) }
        assertEquals(listOf("m3", "m4", "m5"), history.lines("travel bot").map { it.text })
        assertEquals(5, history.unread("Travel Bot"))
        history.markRead("Travel Bot")
        assertEquals(0, history.unread("Travel Bot"))
        history.add("Travel Bot", CarLine(fromMe = false, text = "new", at = 6))
        history.add("Travel Bot", CarLine(fromMe = true, text = "ok", at = 7))
        assertEquals(0, history.unread("Travel Bot"))
    }

    @Test
    fun carSocketUsesItsOwnPath() {
        assertEquals("wss://relay.example/v1/room/ROOM1234/car", carWebSocketUrl("https://relay.example/", "ROOM1234"))
        assertEquals("wss://relay.example/v1/room/ROOM1234/device", deviceWebSocketUrl("https://relay.example", "ROOM1234"))
    }
}

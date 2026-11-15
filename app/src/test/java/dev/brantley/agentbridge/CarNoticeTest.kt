package dev.brantley.agentbridge

import android.app.NotificationManager
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.brantley.agentbridge.car.CarChat
import dev.brantley.agentbridge.car.CarLine
import dev.brantley.agentbridge.car.CarMessages
import dev.brantley.agentbridge.car.CarNoticeAction
import dev.brantley.agentbridge.car.CarNotifier
import dev.brantley.agentbridge.car.CarPoster
import dev.brantley.agentbridge.car.isConnectedCar
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNoticeTest {
    @Test
    fun historicLinesPlusOneNewBotLineExposeOnlyThatLineAsUnread() {
        val lines =
            listOf(
                CarLine(fromMe = true, text = "find a hotel", at = 1),
                CarLine(fromMe = false, text = "Looking.", at = 2),
                CarLine(fromMe = false, text = "Booked the Hyatt.", at = 3),
            )
        val plan = CarMessages.plan(lines, unread = 1, standing = "Talk to Travel Bot.", alert = true)
        val style = CarMessages.style(person("Me", "me"), person("Travel Bot", "car-travel-bot"), plan, now = 9)
        assertEquals(listOf("Booked the Hyatt."), texts(style.messages))
        assertEquals(listOf("find a hotel", "Looking."), texts(style.historicMessages))
        assertEquals(1, style.messages.size)
        assertNotEquals(lines.size, style.messages.size)
        assertNull(style.historicMessages[0].person)
        assertEquals("Travel Bot", style.historicMessages[1].person?.name?.toString())
        assertEquals("Travel Bot", style.messages[0].person?.name?.toString())
        assertNull(plan.standing)
        val clipped = CarMessages.plan(lines, unread = 9, standing = "Talk to Travel Bot.", alert = true)
        assertEquals(lines.map { it.text }, clipped.unread.map { it.text })
        assertTrue(clipped.historic.isEmpty())
        assertNull(clipped.standing)
        val mixed =
            listOf(
                CarLine(fromMe = false, text = "older", at = 1),
                CarLine(fromMe = true, text = "thanks", at = 2),
                CarLine(fromMe = false, text = "Welcome.", at = 3),
            )
        val mixedStyle =
            CarMessages.style(
                person("Me", "me"),
                person("Travel Bot", "car-travel-bot"),
                CarMessages.plan(mixed, unread = 2, standing = null, alert = true),
                now = 9,
            )
        assertNull(mixedStyle.messages[0].person)
        assertEquals("Travel Bot", mixedStyle.messages[1].person?.name?.toString())
        assertEquals("older", mixedStyle.historicMessages.single().text.toString())
    }

    @Test
    fun markReadLeavesNoReadLineUnreadAndOnlyTheStandingPromptWhenPresent() {
        val poster = RecordingPoster()
        val car = car(poster)
        car.bots = listOf("Travel Bot")
        car.onFrame(reply("Travel Bot", "Looking."))
        car.onFrame(reply("Travel Bot", "Booked the Hyatt."))
        car.markRead("Travel Bot")
        val parked = poster.shown.last()
        assertEquals(0, parked.unread)
        assertNull(parked.standing)
        assertFalse(parked.alert)
        val parkedStyle = styleOf(parked, "Travel Bot")
        assertTrue(parkedStyle.messages.isEmpty())
        assertEquals(listOf("Looking.", "Booked the Hyatt."), texts(parkedStyle.historicMessages))

        poster.shown.clear()
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        car.onFrame(reply("Travel Bot", "Welcome back."))
        car.markRead("Travel Bot")
        val driving = poster.shown.last()
        assertEquals("Talk to Travel Bot.", driving.standing)
        assertEquals(0, driving.unread)
        assertFalse(driving.alert)
        val plan = CarMessages.plan(driving.lines, driving.unread, driving.standing, driving.alert)
        val style = CarMessages.style(person("Me", "me"), person("Travel Bot", "bot"), plan, 50)
        assertEquals(listOf("Talk to Travel Bot."), texts(style.messages))
        assertFalse(texts(style.messages).any { it == "Looking." || it == "Booked the Hyatt." || it == "Welcome back." })
        assertTrue(texts(style.historicMessages).containsAll(listOf("Looking.", "Booked the Hyatt.", "Welcome back.")))
        assertTrue(plan.silent)
        assertFalse(plan.importanceHigh)
        assertTrue(plan.actions.contains(CarNoticeAction.REPLY))
    }

    @Test
    fun standingLineIsTheOnlyUnreadMessageAndReplyStays() {
        val lines = listOf(CarLine(true, "find a hotel", 1), CarLine(false, "Looking.", 2))
        val plan = CarMessages.plan(lines, unread = 0, standing = "Talk to Grok Bot.", alert = false)
        val style = CarMessages.style(person("Me", "me"), person("Grok Bot", "bot"), plan, now = 8)
        assertEquals(listOf("Talk to Grok Bot."), texts(style.messages))
        assertEquals(listOf("find a hotel", "Looking."), texts(style.historicMessages))
        assertEquals("Grok Bot", style.messages.single().person?.name?.toString())
        assertNull(style.historicMessages[0].person)
        assertTrue(plan.actions.contains(CarNoticeAction.REPLY))
        assertTrue(plan.actions.contains(CarNoticeAction.MARK_AS_READ))
        assertTrue(plan.silent)
        assertFalse(plan.importanceHigh)
    }

    @Test
    fun unreadBotLinesAreNotJoinedByTheStandingLine() {
        val lines =
            listOf(
                CarLine(true, "find a hotel", 1),
                CarLine(false, "Looking.", 2),
                CarLine(false, "Booked the Hyatt.", 3),
            )
        val plan = CarMessages.plan(lines, unread = 2, standing = "Talk to Travel Bot.", alert = true)
        val style = CarMessages.style(person("Me", "me"), person("Travel Bot", "bot"), plan, now = 8)
        assertEquals(listOf("Looking.", "Booked the Hyatt."), texts(style.messages))
        assertEquals(listOf("find a hotel"), texts(style.historicMessages))
        assertFalse(texts(style.messages).any { it.startsWith("Talk to") })
        assertNull(plan.standing)
        assertTrue(plan.actions.contains(CarNoticeAction.REPLY))
        assertFalse(plan.silent)
        assertTrue(plan.importanceHigh)

        val poster = RecordingPoster()
        val car = car(poster)
        car.bots = listOf("Travel Bot")
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        poster.shown.clear()
        car.onFrame(reply("Travel Bot", "Booked the Hyatt."))
        val last = poster.shown.single()
        assertTrue(last.alert)
        assertNull(last.standing)
        assertEquals(1, last.unread)
        val live = styleOf(last, "Travel Bot")
        assertEquals(listOf("Booked the Hyatt."), texts(live.messages))
        assertTrue(live.historicMessages.isEmpty())
    }

    @Test
    fun sayWhileConnectedLeavesTheStandingLineAsTheOnlyUnread() {
        val poster = RecordingPoster()
        val car = car(poster)
        car.bots = listOf("Grok Bot")
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        car.say("Grok Bot", "find a hotel")
        val last = poster.shown.last()
        assertEquals(0, last.unread)
        assertEquals("Talk to Grok Bot.", last.standing)
        assertFalse(last.alert)
        val style = styleOf(last, "Grok Bot")
        assertEquals(listOf("Talk to Grok Bot."), texts(style.messages))
        assertEquals(listOf("find a hotel"), texts(style.historicMessages))
        assertNull(style.historicMessages.single().person)
        assertEquals("Grok Bot", style.messages.single().person?.name?.toString())
    }

    @Test
    fun projectionOrNativePostsOncePerBotAndSocketReconnectDoesNot() {
        val poster = RecordingPoster()
        val car = car(poster)
        car.bots = listOf("Grok Bot", "Travel Bot")
        car.history.add("Travel Bot", CarLine(false, "Booked.", 1))
        assertFalse(isConnectedCar(CarConnection.CONNECTION_TYPE_NOT_CONNECTED))
        assertTrue(isConnectedCar(CarConnection.CONNECTION_TYPE_PROJECTION))
        assertTrue(isConnectedCar(CarConnection.CONNECTION_TYPE_NATIVE))
        car.onCarConnection(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        assertTrue(poster.shown.isEmpty())
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertEquals(listOf("Grok Bot"), poster.shown.map { it.bot })
        assertEquals("Talk to Grok Bot.", poster.shown.single().standing)
        assertFalse(poster.shown.single().alert)
        assertEquals(0, poster.shown.single().unread)
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        car.onCarConnection(CarConnection.CONNECTION_TYPE_NATIVE)
        assertEquals(1, poster.shown.size)
        car.attach { true }
        car.onConnected()
        assertEquals(1, poster.shown.size)
        assertTrue(poster.cancelled.isEmpty())
    }

    @Test
    fun disconnectCancelsStandingPromptsAndLeavesUnreadReplies() {
        val poster = RecordingPoster()
        val car = car(poster)
        car.bots = listOf("Grok Bot", "Travel Bot")
        car.history.add("Travel Bot", CarLine(false, "Booked.", 1))
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        car.onCarConnection(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        assertEquals(listOf("Grok Bot"), poster.cancelled)
        assertTrue(poster.cancelledAll.isEmpty())
        poster.shown.clear()
        poster.cancelled.clear()
        car.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertEquals(listOf("Grok Bot"), poster.shown.map { it.bot })
        assertEquals("Talk to Grok Bot.", poster.shown.single().standing)
    }

    @Test
    fun settingsOffAndServiceStopCancelEveryBotAndDoNotRepost() {
        val settingsPoster = RecordingPoster()
        val settingsCar = car(settingsPoster)
        settingsCar.bots = listOf("Travel Bot", "Work Bot")
        settingsCar.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        settingsCar.onSettingsDisabled(listOf("Travel Bot", "Work Bot"))
        assertEquals(listOf(listOf("Travel Bot", "Work Bot")), settingsPoster.cancelledAll)
        settingsPoster.shown.clear()
        settingsCar.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        settingsCar.markRead("Travel Bot")
        settingsCar.say("Travel Bot", "hello")
        assertTrue(settingsPoster.shown.isEmpty())

        val servicePoster = RecordingPoster()
        val serviceCar = car(servicePoster)
        serviceCar.bots = listOf("Grok Bot")
        serviceCar.onCarConnection(CarConnection.CONNECTION_TYPE_NATIVE)
        serviceCar.onServiceStopped()
        assertEquals(listOf(listOf("Grok Bot")), servicePoster.cancelledAll)
        servicePoster.shown.clear()
        serviceCar.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertTrue(servicePoster.shown.isEmpty())
        serviceCar.allowPosts()
        serviceCar.onCarConnection(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertEquals("Talk to Grok Bot.", servicePoster.shown.single().standing)
    }

    @Test
    fun silentPostsAreNeverHighImportance() {
        val quiet = CarMessages.plan(emptyList(), 0, "Talk to Grok Bot.", alert = false)
        assertTrue(quiet.silent)
        assertFalse(quiet.importanceHigh)
        assertEquals(CarNotifier.CHANNEL_QUIET, CarMessages.channel(false))
        assertEquals(CarNotifier.CHANNEL_ID, CarMessages.channel(true))
        assertEquals("agent_bridge_car_chat", CarNotifier.CHANNEL_ID)
        assertNotEquals(NotificationCompat.PRIORITY_HIGH, CarMessages.priority(false))
        assertEquals(NotificationCompat.PRIORITY_LOW, CarMessages.priority(false))
        assertEquals(NotificationCompat.PRIORITY_HIGH, CarMessages.priority(true))
        assertTrue(CarMessages.channelImportance(false) < NotificationManager.IMPORTANCE_HIGH)
        assertNotEquals(NotificationManager.IMPORTANCE_HIGH, CarNotifier.QUIET_IMPORTANCE)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, CarMessages.channelImportance(true))
        val loud = CarMessages.plan(listOf(CarLine(false, "Done.", 1)), 1, "Talk to Grok Bot.", alert = true)
        assertFalse(loud.silent)
        assertTrue(loud.importanceHigh)
        assertNull(loud.standing)
    }

    @Test
    fun actionStopAndSettingsToggleBothCancel() {
        val service = source("src/main/java/dev/brantley/agentbridge/car/CarChatService.kt")
        val shutdown = service.substringAfter("private fun shutdown()").substringBefore("private fun ensureChannel")
        assertTrue(shutdown.contains("onServiceStopped()"))
        val stop = service.substringAfter("ACTION_STOP").substringBefore("ensureChannel()")
        assertTrue(stop.contains("shutdown()"))
        val watch = service.substringAfter("private fun watchCar()").substringBefore("private fun unwatchCar")
        assertTrue(watch.contains("allowPosts()"))
        assertFalse(service.contains("bots.forEach { app.car.prompt"))
        val settings = source("src/main/java/dev/brantley/agentbridge/ui/SettingsScreen.kt")
        val off = settings.substringAfter("RowSwitch(\"Car chat\"").substringAfter("} else {").substringBefore("OutlinedTextField")
        assertTrue(off.contains("onSettingsDisabled"))
        assertTrue(source("src/main/res/values/strings.xml").contains("Talk to %1\$s."))
        assertFalse(source("src/main/java/dev/brantley/agentbridge/car/CarNotifier.kt").contains("Ready. Reply"))
        val manifest = source("src/main/AndroidManifest.xml")
        val auto = source("src/main/res/xml/automotive_app_desc.xml")
        assertFalse(manifest.contains("RECORD_AUDIO"))
        assertFalse(manifest.contains("CarAppService"))
        assertTrue(manifest.contains("androidx.car.app.CarAppPermissionActivity"))
        assertTrue(manifest.contains("CarAppNotificationBroadcastReceiver"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
        assertTrue(auto.contains("notification"))
        assertFalse(auto.contains("template"))
    }

    private fun car(poster: RecordingPoster) = CarChat(poster, standingText = { "Talk to $it." }, clock = { 10 })

    private fun reply(bot: String, text: String) = """{"type":"car.reply","bot":"$bot","text":"$text"}"""

    private fun person(name: String, key: String) = Person.Builder().setName(name).setKey(key).build()

    private fun styleOf(shown: RecordingPoster.Shown, botName: String) =
        CarMessages.style(
            person("Me", "me"),
            person(botName, "bot"),
            CarMessages.plan(shown.lines, shown.unread, shown.standing, shown.alert),
            now = 50,
        )

    private fun texts(messages: List<NotificationCompat.MessagingStyle.Message>) = messages.map { it.text.toString() }

    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"), File("../$path"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("missing $path from ${File(".").absolutePath}")
    }
}

private class RecordingPoster : CarPoster {
    val shown = mutableListOf<Shown>()
    val cancelled = mutableListOf<String>()
    val cancelledAll = mutableListOf<List<String>>()

    data class Shown(
        val bot: String,
        val lines: List<CarLine>,
        val unread: Int,
        val alert: Boolean,
        val standing: String?,
    )

    override fun show(bot: String, lines: List<CarLine>, unread: Int, alert: Boolean, standing: String?) {
        shown += Shown(bot, lines, unread, alert, standing)
    }

    override fun cancel(bot: String) {
        cancelled += bot
    }

    override fun cancelAll(bots: List<String>) {
        cancelledAll += bots.toList()
    }
}

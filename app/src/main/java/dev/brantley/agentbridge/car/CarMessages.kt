package dev.brantley.agentbridge.car

import android.app.NotificationManager
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

enum class CarNoticeAction { REPLY, MARK_AS_READ }

data class CarNotice(
    val historic: List<CarLine>,
    val unread: List<CarLine>,
    val standing: String?,
    val silent: Boolean,
    val importanceHigh: Boolean,
    val actions: List<CarNoticeAction>,
)

object CarMessages {
    fun plan(lines: List<CarLine>, unread: Int, standing: String?, alert: Boolean): CarNotice {
        val count = unread.coerceAtLeast(0).coerceAtMost(lines.size)
        val split = lines.size - count
        return CarNotice(
            historic = lines.take(split),
            unread = lines.drop(split),
            standing = standing?.takeIf { count == 0 && it.isNotBlank() },
            silent = !alert,
            importanceHigh = alert,
            actions = listOf(CarNoticeAction.REPLY, CarNoticeAction.MARK_AS_READ),
        )
    }

    fun style(me: Person, bot: Person, plan: CarNotice, now: Long): NotificationCompat.MessagingStyle {
        val messaging = NotificationCompat.MessagingStyle(me).setGroupConversation(false)
        plan.historic.forEach { line -> messaging.addHistoricMessage(bubble(line, bot)) }
        plan.unread.forEach { line -> messaging.addMessage(bubble(line, bot)) }
        val standing = plan.standing
        if (standing != null) {
            messaging.addMessage(NotificationCompat.MessagingStyle.Message(standing, now, bot))
        }
        return messaging
    }

    fun channel(importanceHigh: Boolean): String =
        if (importanceHigh) CarNotifier.CHANNEL_ID else CarNotifier.CHANNEL_QUIET

    fun priority(importanceHigh: Boolean): Int =
        if (importanceHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

    fun channelImportance(importanceHigh: Boolean): Int =
        if (importanceHigh) NotificationManager.IMPORTANCE_HIGH else CarNotifier.QUIET_IMPORTANCE

    private fun bubble(line: CarLine, bot: Person): NotificationCompat.MessagingStyle.Message =
        NotificationCompat.MessagingStyle.Message(line.text, line.at, if (line.fromMe) null else bot)
}

internal fun isConnectedCar(type: Int): Boolean =
    type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE

package dev.brantley.agentbridge.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.brantley.agentbridge.R
import dev.brantley.agentbridge.ui.MainActivity

/** Android Auto messaging notifications and conversation shortcuts, one per bot. */
class CarNotifier(private val context: Context) {
    private val me: Person by lazy { Person.Builder().setName(context.getString(R.string.car_me)).setKey("me").build() }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.car_channel), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.car_channel_desc)
        manager.createNotificationChannel(channel)
    }

    fun person(bot: String): Person =
        Person.Builder()
            .setName(bot)
            .setKey(CarBots.shortcutId(bot))
            .setBot(true)
            .setImportant(true)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher))
            .build()

    /** Long-lived conversation shortcuts, so the system and Assistant know each bot as a contact. */
    fun publishShortcuts(bots: List<String>) {
        val keep = bots.map { CarBots.shortcutId(it) }.toSet()
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith("car-") && it !in keep }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
        bots.forEachIndexed { index, bot ->
            val id = CarBots.shortcutId(bot)
            val shortcut =
                ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(bot)
                    .setLongLabel(bot)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher))
                    .setPerson(person(bot))
                    .setLongLived(true)
                    .setRank(index)
                    .setLocusId(LocusIdCompat(id))
                    .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, SHARE_CATEGORY))
                    .addCapabilityBinding("actions.intent.CREATE_MESSAGE", "message.recipient.name", listOf(bot))
                    .setIntent(
                        Intent(context, CarComposeActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra(CarComposeActivity.EXTRA_BOT, bot),
                    )
                    .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    fun show(bot: String, lines: List<CarLine>, unread: Int, alert: Boolean) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val botPerson = person(bot)
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(false)
        if (lines.isEmpty()) {
            style.addMessage(NotificationCompat.MessagingStyle.Message("Ready. Reply to send me a task.", System.currentTimeMillis(), botPerson))
        } else {
            lines.forEach { line ->
                style.addMessage(NotificationCompat.MessagingStyle.Message(line.text, line.at, if (line.fromMe) null else botPerson))
            }
        }
        val id = CarBots.shortcutId(bot)
        val code = CarBots.notificationId(bot)
        val replyInput = RemoteInput.Builder(KEY_TEXT).setLabel(context.getString(R.string.car_reply_label)).build()
        val replyIntent =
            PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, CarActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_BOT, bot),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        val reply =
            NotificationCompat.Action.Builder(R.drawable.ic_stat_bridge, context.getString(R.string.car_reply_label), replyIntent)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .addRemoteInput(replyInput)
                .build()
        val readIntent =
            PendingIntent.getBroadcast(
                context,
                code + 1,
                Intent(context, CarActionReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_BOT, bot),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val markRead =
            NotificationCompat.Action.Builder(R.drawable.ic_stat_bridge, context.getString(R.string.car_mark_read), readIntent)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        val open =
            PendingIntent.getActivity(
                context,
                code + 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setShortcutId(id)
                .setLocusId(LocusIdCompat(id))
                .addPerson(botPerson)
                .setContentIntent(open)
                .addAction(reply)
                .addInvisibleAction(markRead)
                .setNumber(unread)
                .setSilent(!alert)
                .setOnlyAlertOnce(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(code, notification) }
    }

    fun cancelAll(bots: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        bots.forEach { manager.cancel(CarBots.notificationId(it)) }
    }

    companion object {
        const val CHANNEL_ID = "agent_bridge_car_chat"
        const val KEY_TEXT = "car_reply_text"
        const val EXTRA_BOT = "dev.brantley.agentbridge.car.BOT"
        const val ACTION_REPLY = "dev.brantley.agentbridge.car.REPLY"
        const val ACTION_READ = "dev.brantley.agentbridge.car.READ"
        const val SHARE_CATEGORY = "dev.brantley.agentbridge.category.CAR_CHAT"
    }
}

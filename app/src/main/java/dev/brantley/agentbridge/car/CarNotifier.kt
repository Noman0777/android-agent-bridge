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

interface CarPoster {
    fun ensureChannel() {}

    fun publishShortcuts(bots: List<String>) {}

    fun show(bot: String, lines: List<CarLine>, unread: Int, alert: Boolean, standing: String?)

    fun cancel(bot: String)

    fun cancelAll(bots: List<String>)
}

/** Android Auto messaging notifications and conversation shortcuts, one per bot. */
class CarNotifier(private val context: Context) : CarPoster {
    private val me: Person by lazy { Person.Builder().setName(context.getString(R.string.car_me)).setKey("me").build() }
    private val postedChannel = HashMap<Int, String>()
    private val postLock = Any()

    override fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alerts = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.car_channel),
            CarMessages.channelImportance(true),
        )
        alerts.description = context.getString(R.string.car_channel_desc)
        manager.createNotificationChannel(alerts)
        val quiet = NotificationChannel(
            CHANNEL_QUIET,
            context.getString(R.string.car_channel_quiet),
            CarMessages.channelImportance(false),
        )
        quiet.description = context.getString(R.string.car_channel_quiet_desc)
        quiet.setSound(null, null)
        quiet.enableVibration(false)
        manager.createNotificationChannel(quiet)
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
    override fun publishShortcuts(bots: List<String>) {
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

    override fun show(bot: String, lines: List<CarLine>, unread: Int, alert: Boolean, standing: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val plan = CarMessages.plan(lines, unread, standing, alert)
        if (plan.historic.isEmpty() && plan.unread.isEmpty() && plan.standing == null) return
        ensureChannel()
        val botPerson = person(bot)
        val style = CarMessages.style(me, botPerson, plan, System.currentTimeMillis())
        val id = CarBots.shortcutId(bot)
        val code = CarBots.notificationId(bot)
        val reply = replyAction(bot, code)
        val markRead = markReadAction(bot, code)
        val open =
            PendingIntent.getActivity(
                context,
                code + 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, CarMessages.channel(plan.importanceHigh))
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setShortcutId(id)
                .setLocusId(LocusIdCompat(id))
                .addPerson(botPerson)
                .setContentIntent(open)
                .setNumber(unread)
                .setSilent(plan.silent)
                .setOnlyAlertOnce(plan.silent)
                .setPriority(CarMessages.priority(plan.importanceHigh))
        plan.actions.forEach { action ->
            when (action) {
                CarNoticeAction.REPLY -> builder.addAction(reply)
                CarNoticeAction.MARK_AS_READ -> builder.addInvisibleAction(markRead)
            }
        }
        val channel = CarMessages.channel(plan.importanceHigh)
        val notification = builder.build()
        val manager = NotificationManagerCompat.from(context)
        synchronized(postLock) {
            if (postedChannel[code] != channel) manager.cancel(code)
            postedChannel[code] = channel
            runCatching { manager.notify(code, notification) }
        }
    }

    override fun cancel(bot: String) {
        val code = CarBots.notificationId(bot)
        synchronized(postLock) { postedChannel.remove(code) }
        NotificationManagerCompat.from(context).cancel(code)
    }

    override fun cancelAll(bots: List<String>) {
        bots.forEach { cancel(it) }
    }

    private fun replyAction(bot: String, code: Int): NotificationCompat.Action {
        val replyInput = RemoteInput.Builder(KEY_TEXT).setLabel(context.getString(R.string.car_reply_label)).build()
        val replyIntent =
            PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, CarActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_BOT, bot),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_bridge, context.getString(R.string.car_reply_label), replyIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(replyInput)
            .build()
    }

    private fun markReadAction(bot: String, code: Int): NotificationCompat.Action {
        val readIntent =
            PendingIntent.getBroadcast(
                context,
                code + 1,
                Intent(context, CarActionReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_BOT, bot),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_bridge, context.getString(R.string.car_mark_read), readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "agent_bridge_car_chat"
        const val CHANNEL_QUIET = "agent_bridge_car_chat_quiet"
        const val QUIET_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT
        const val KEY_TEXT = "car_reply_text"
        const val EXTRA_BOT = "dev.brantley.agentbridge.car.BOT"
        const val ACTION_REPLY = "dev.brantley.agentbridge.car.REPLY"
        const val ACTION_READ = "dev.brantley.agentbridge.car.READ"
        const val SHARE_CATEGORY = "dev.brantley.agentbridge.category.CAR_CHAT"
    }
}

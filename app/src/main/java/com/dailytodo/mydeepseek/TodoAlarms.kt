package com.dailytodo.mydeepseek

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

object TodoAlarms {
    const val DUE = "com.dailytodo.mydeepseek.TODO_DUE"
    const val DONE = "com.dailytodo.mydeepseek.TODO_DONE"
    private const val CHANNEL = "pet-todo-reminders"
    private fun notifications(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)
    fun channel(context: Context) {
        notifications(context).createNotificationChannel(NotificationChannel(CHANNEL, "待办到期提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "你为待办选择的提醒时间" })
    }
    fun notificationsAllowed(context: Context): Boolean {
        channel(context)
        return notifications(context).areNotificationsEnabled() &&
            notifications(context).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun alarm(context: Context) = PendingIntent.getBroadcast(context, 2000,
        Intent(context, TodoReceiver::class.java).setAction(DUE).setData(Uri.parse("dshpet://todo/alarm")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(context: Context, records: List<JSONObject>) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = alarm(context); manager.cancel(operation)
        if (!notificationsAllowed(context)) return
        val earliest = records.filter(TodoRules::needsReminder).minOfOrNull { TodoRules.due(it)!! } ?: return
        val at = maxOf(earliest, System.currentTimeMillis() + 1000)
        try {
            if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }
    fun cancelNotification(context: Context, id: String) { notifications(context).cancel("todo-$id", 0) }
    fun notifyDue(context: Context, records: List<JSONObject>): Boolean {
        if (records.isEmpty() || !notificationsAllowed(context)) return false
        channel(context)
        val open = PendingIntent.getActivity(context, 2001, Intent(context, TodoActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pet_notification)
            .setContentTitle("待办到期了").setContentText("打开查看你的待办").build()
        try {
            if (records.size > 5) {
                val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pet_notification)
                    .setContentTitle("${records.size} 件小事到时间了").setContentText("轻点查看待办清单")
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(public).build()
                notifications(context).notify("todo-summary", 0, notification)
            } else for (row in records) {
                val id = row.getString("id")
                val done = PendingIntent.getBroadcast(context, 0, Intent(context, TodoReceiver::class.java)
                    .setAction(DONE).setData(Uri.parse("dshpet://todo/$id")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pet_notification)
                    .setContentTitle("小事到时间啦").setContentText(row.getString("text"))
                    .setStyle(Notification.BigTextStyle().bigText(row.getString("text")))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(public).addAction(Notification.Action.Builder(null, "已完成", done).build()).build()
                notifications(context).notify("todo-$id", 0, notification)
            }
            return true
        } catch (_: SecurityException) { return false }
    }
}

class TodoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repository = PetTodos.get(context)
        if (intent.action == TodoAlarms.DONE) {
            val id = intent.data?.lastPathSegment.orEmpty()
            repository.update(id, JSONObject().put("done", true)) { _, _ -> pending.finish() }
        } else if (intent.action == TodoAlarms.DUE) repository.deliver { pending.finish() }
        else repository.reschedule { pending.finish() }
    }
}

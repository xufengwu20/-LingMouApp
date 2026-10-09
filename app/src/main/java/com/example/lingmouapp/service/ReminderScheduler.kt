package com.example.lingmouapp.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.lingmouapp.model.Reminder
import java.util.concurrent.atomic.AtomicInteger

/**
 * AlarmManager 定时提醒。不做任何持久化：进程被杀掉后提醒会丢，
 * 这符合「先跑通逻辑、暂不涉及数据库」的当前目标。
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private val idGenerator = AtomicInteger(1)

    /** Android 12+ 需要用户在系统设置里额外授予「闹钟和提醒」权限 */
    fun canScheduleExact(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(durationMs: Long, label: String): Reminder {
        val id = idGenerator.getAndIncrement()
        val triggerAt = System.currentTimeMillis() + durationMs
        val exact = canScheduleExact()

        alarmManager?.let { am ->
            val pi = pendingIntent(id, label)
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 没有精确闹钟权限时退化为非精确闹钟，功能不至于失效
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        return Reminder(
            id = id,
            label = label,
            triggerAtMillis = triggerAt,
            exact = exact
        )
    }

    fun cancel(id: Int) {
        val am = alarmManager ?: return
        val pi = pendingIntent(id, "")
        am.cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(id: Int, label: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_LABEL, label)
        }
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

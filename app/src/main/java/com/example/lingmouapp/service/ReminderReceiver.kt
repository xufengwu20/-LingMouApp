package com.example.lingmouapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 定时提醒到点时由 AlarmManager 触发：发通知 + 更新界面状态。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "定时提醒" }
        NotificationHelper(context.applicationContext).notifyReminder(label)
        LingMouEngine.onReminderFired(id, label)
    }

    companion object {
        const val ACTION_FIRE = "com.example.lingmouapp.action.REMINDER_FIRE"
        const val EXTRA_ID = "extra_reminder_id"
        const val EXTRA_LABEL = "extra_reminder_label"
    }
}

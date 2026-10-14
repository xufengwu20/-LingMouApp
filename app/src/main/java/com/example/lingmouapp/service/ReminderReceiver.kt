package com.example.lingmouapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时提醒到点时由 AlarmManager 触发：发通知 + 更新界面状态。
 *
 * 注意冷启动场景：用户在息屏状态下等待提醒时，App 进程很可能已经被系统回收，
 * 这个广播会把进程重新拉起来。此时 Application.onCreate() 会先跑（渠道已建好），
 * 但为了万无一失，这里再显式确保一次：
 *   - 通知渠道不存在时，Android 8+ 会**静默丢弃**通知，是最容易踩的坑；
 *   - 引擎未初始化时，状态写入和日志会落在临时的 StateFlow 上。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        val app = context.applicationContext
        LingMouEngine.init(app)
        NotificationHelper(app).ensureChannels()

        val id = intent.getIntExtra(EXTRA_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "定时提醒" }

        NotificationHelper(app).notifyReminder(label)
        LingMouEngine.onReminderFired(id, label)
    }

    companion object {
        const val ACTION_FIRE = "com.example.lingmouapp.action.REMINDER_FIRE"
        const val EXTRA_ID = "extra_reminder_id"
        const val EXTRA_LABEL = "extra_reminder_label"
    }
}

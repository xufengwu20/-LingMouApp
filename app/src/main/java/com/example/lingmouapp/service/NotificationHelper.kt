package com.example.lingmouapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.lingmouapp.MainActivity
import com.example.lingmouapp.R

/**
 * 统一管理两类通知：
 *  - status 渠道：前台服务常驻通知，显示当前模式与实时状态（静音、不打扰）
 *  - alert  渠道：有人靠近 / 定时提醒这类需要立刻感知的事件
 */
class NotificationHelper(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val status = NotificationChannel(
            CHANNEL_STATUS,
            "运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "前台服务常驻通知，显示当前模式、人脸数与提示状态"
            setShowBadge(false)
            enableVibration(false)
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "事件提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "有人靠近、定时提醒等需要立刻知道的事件"
            enableVibration(true)
        }
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(alert)
    }

    fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildStatus(title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_lingmou)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())
            .build()

    fun updateStatus(title: String, text: String) {
        post(ID_STATUS, buildStatus(title, text))
    }

    fun notifyIntruder(faceCount: Int) {
        val body = "画面里出现了 $faceCount 张人脸，注意一下周围。"
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_lingmou)
            .setContentTitle("有人靠近")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        post(ID_INTRUDER, n)
    }

    fun notifyReminder(label: String) {
        val body = "到时间了：$label"
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_lingmou)
            .setContentTitle("定时提醒")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        post(ID_REMINDER, n)
    }

    private fun post(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 未授予，忽略
        }
    }

    companion object {
        const val CHANNEL_STATUS = "lingmou_status"
        const val CHANNEL_ALERT = "lingmou_alert"
        const val ID_STATUS = 1001
        const val ID_INTRUDER = 1002
        const val ID_REMINDER = 1003
    }
}

package com.example.lingmouapp.service

import android.service.notification.NotificationListenerService

/**
 * 这个类本身不做任何通知处理。
 *
 * 它的存在只是为了给 MediaSessionManager.getActiveSessions(ComponentName) 提供一个合法的
 * ComponentName —— 只有「已授权的通知监听服务」所属的应用，才能读取系统中其他应用的媒体会话。
 *
 * 需要用户手动开启：设置 → 通知与状态栏 → 通知使用权 → 灵眸。
 */
class LingMouNotificationListener : NotificationListenerService()

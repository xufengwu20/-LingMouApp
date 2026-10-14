package com.example.lingmouapp.service

import android.service.notification.NotificationListenerService

/**
 * 通知监听服务（NotificationListenerService）。
 *
 * 它本身不处理任何通知，存在的唯一目的是拿到「通知使用权」这个特权：
 * 只有已授权的通知监听服务所属应用，才能调用
 * `MediaSessionManager.getActiveSessions(ComponentName)` 读取**其他应用**的媒体会话。
 * 跨应用暂停 / 播放 B 站、华为视频，全靠它。
 *
 * 授权状态变化时，系统会回调下面两个方法 —— 用它把状态**立刻**同步到界面，
 * 比轮询快得多，也顺便解决了「从设置页返回时状态还没刷新」的体感问题。
 * 注意这两个回调可能不在主线程，所以只调用线程安全的引擎方法。
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        // 进程可能是被系统为了绑定监听服务而拉起来的，先保证引擎可用
        LingMouEngine.init(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LingMouEngine.onNotificationAccessChanged()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LingMouEngine.onNotificationAccessChanged()
    }
}

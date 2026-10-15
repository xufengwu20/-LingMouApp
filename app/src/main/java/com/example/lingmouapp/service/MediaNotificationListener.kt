package com.example.lingmouapp.service

import android.service.notification.NotificationListenerService
import android.util.Log

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
 *
 * 另外这里记录 [connected]：**「设置里勾选了」≠「监听服务还连着」**。
 * ColorOS 在设备重启 / 系统回收后常出现「开关仍显示已授权、但服务已断开」，
 * 此时 getActiveSessions 会静默返回空列表，跨应用控制全部失效。
 * 查询方（MediaSessionController）会把这个标志打进出日志里，一眼就能分辨。
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        // 进程可能是被系统为了绑定监听服务而拉起来的，先保证引擎可用
        LingMouEngine.init(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Log.i(TAG, "通知监听服务已连接（系统绑定成功，getActiveSessions 可用）")
        LingMouEngine.onNotificationAccessChanged()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        Log.w(
            TAG,
            "通知监听服务已断开 —— 常见于设备重启/系统回收后，即使设置里仍显示已授权。" +
                "此时 getActiveSessions 返回空列表，跨应用控制失效。" +
                "恢复方法：通知使用权把灵眸关掉再打开一次。"
        )
        LingMouEngine.onNotificationAccessChanged()
    }

    companion object {
        /** logcat 过滤：adb logcat -s LingMouMedia */
        const val TAG = "LingMouMedia"

        /** 监听服务当前是否真的被系统绑定（线程安全读） */
        @Volatile
        var connected: Boolean = false
    }
}

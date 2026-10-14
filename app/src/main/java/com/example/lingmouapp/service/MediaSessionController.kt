package com.example.lingmouapp.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.example.lingmouapp.model.MediaSessionSnapshot

/**
 * 跨应用媒体控制。
 *
 * 权限前提：本应用必须在「设置 → 通知 → 通知使用权」里被勾选。
 * 只有已授权的通知监听服务所属应用，才能通过
 * MediaSessionManager.getActiveSessions(ComponentName) 读到别的应用的媒体会话。
 *
 * 拿到 MediaController 之后，pause / play / seekTo 会真的作用到
 * B 站、华为视频、QQ 音乐这类正在播放的应用上。
 */
class MediaSessionController(private val context: Context) {

    private val manager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private var changeListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /** 上一次查询失败的原因，用于把「明明授权了却拿不到会话」这种疑难直接显示出来 */
    @Volatile
    private var lastQueryError: String? = null

    // ------------------------------------------------------------------ 权限

    /** 用户是否已在系统设置里勾选本应用的通知使用权 */
    fun hasNotificationAccess(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: Exception) {
        false
    }

    private fun listenerComponent(): ComponentName =
        ComponentName(context, MediaNotificationListener::class.java)

    // ------------------------------------------------------------------ 取会话

    private data class Query(val controllers: List<MediaController>, val error: String?)

    /**
     * 查询活跃会话。所有异常都在这里被吸收，调用方不需要 try，也不会因此崩溃。
     *
     * SecurityException 单独标记：它意味着「系统里勾了通知使用权，但不认这个监听组件」，
     * 典型场景是应用改过包名/组件名，或重装后残留了旧条目 —— 此时把开关关掉再打开即可。
     * 不区分出来的话，界面只会说「没有媒体会话」，让人完全找不到方向。
     */
    private fun query(): Query {
        val m = manager ?: return Query(emptyList(), "no_service")
        if (!hasNotificationAccess()) return Query(emptyList(), "no_permission")
        return try {
            Query(m.getActiveSessions(listenerComponent()), null)
        } catch (e: SecurityException) {
            lastQueryError = e.message ?: "SecurityException"
            Query(emptyList(), "security")
        } catch (e: Exception) {
            lastQueryError = e.message ?: e.javaClass.simpleName
            Query(emptyList(), "error")
        }
    }

    /** 原始会话列表（已授权时） */
    fun activeControllers(): List<MediaController> = query().controllers

    /**
     * 候选会话：排除本应用自己，并把系统 UI 这类「不是真正在放视频」的会话排到最后。
     *
     * 之所以是「排到最后」而不是「直接排除」：极端情况下它可能是唯一能拿到的会话，
     * 直接丢掉会让排查无从下手。
     */
    private fun candidates(): List<MediaController> =
        query().controllers
            .filter { it.packageName != context.packageName }
            .sortedBy { if (SYSTEM_PACKAGES.contains(it.packageName)) 1 else 0 }

    /**
     * 选一个「最可能正在放视频」的会话。
     *
     * 优先级：正在播放 > 处于可控制状态（暂停/缓冲/快进快退/停止） > 任意一个。
     * 最后那档是刻意保留的：不少国产 App 的 playbackState 上报不全
     * （B 站某些版本常常是 STATE_NONE），但 transportControls 依然有效，
     * 所以不能因为状态不标准就放弃它。
     */
    private fun primaryController(): MediaController? {
        val list = candidates()
        if (list.isEmpty()) return null
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        list.firstOrNull { isTransportable(it.playbackState?.state) }?.let { return it }
        return list.first()
    }

    private fun isTransportable(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    // ------------------------------------------------------------------ 诊断

    /** 取一次快照；界面和日志都靠它，避免重复 IPC */
    fun snapshot(): MediaSessionSnapshot {
        if (manager == null) {
            return MediaSessionSnapshot(summary = "这台设备不支持 MediaSessionManager")
        }
        if (!hasNotificationAccess()) {
            return MediaSessionSnapshot(
                summary = "未授予通知使用权，跨应用媒体控制不可用",
                detail = "未授权"
            )
        }

        val result = query()
        if (result.error == "security") {
            return MediaSessionSnapshot(
                summary = "系统拒绝了媒体会话查询：监听组件未被系统接受。" +
                    "请去「通知使用权」把灵眸关掉再重新打开一次",
                detail = "SecurityException: ${lastQueryError ?: ""}"
            )
        }
        if (result.error != null && result.error != "no_permission") {
            return MediaSessionSnapshot(
                summary = "查询媒体会话失败：${lastQueryError ?: result.error}",
                detail = "error=${result.error}"
            )
        }

        val list = candidates()
        if (list.isEmpty()) {
            return MediaSessionSnapshot(
                summary = "没有可下发的媒体会话（先在 B 站 / 华为视频里播放一个视频，再回到这里刷新）",
                sessionCount = 0,
                detail = "无"
            )
        }

        val chosen = primaryController()
        val pkg = chosen?.packageName
        val state = stateName(chosen?.playbackState?.state)
        return MediaSessionSnapshot(
            summary = "已授权 · 发现 ${list.size} 个会话 · 目标：$pkg（$state）",
            sessionCount = list.size,
            targetPackage = pkg,
            targetState = state,
            detail = list.joinToString("；") { c ->
                "${c.packageName ?: "?"}=${stateName(c.playbackState?.state)}"
            }
        )
    }

    fun hasActiveSession(): Boolean = primaryController() != null

    // ------------------------------------------------------------------ 监听

    /**
     * 注册「活动会话变化」监听。授权状态一变就能立刻反映到界面上，不用等轮询。
     * 重复调用是安全的。
     */
    fun ensureWatching(onChanged: () -> Unit) {
        if (!hasNotificationAccess()) {
            stopWatching()
            return
        }
        if (changeListener != null) return
        val m = manager ?: return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { onChanged() }
        try {
            m.addOnActiveSessionsChangedListener(
                listener,
                listenerComponent(),
                Handler(Looper.getMainLooper())
            )
            changeListener = listener
        } catch (e: Exception) {
            changeListener = null
        }
    }

    fun stopWatching() {
        val listener = changeListener ?: return
        changeListener = null
        try {
            manager?.removeOnActiveSessionsChangedListener(listener)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ------------------------------------------------------------------ 指令

    fun pause(): Boolean {
        val c = primaryController() ?: return false
        return try {
            c.transportControls.pause()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun play(): Boolean {
        val c = primaryController() ?: return false
        return try {
            c.transportControls.play()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 回退：用 seekTo(当前位置 - seconds) 实现，比 ACTION_REWIND 兼容性好得多 */
    fun rewind(seconds: Int): Boolean {
        val c = primaryController() ?: return false
        return try {
            val position = c.playbackState?.position ?: 0L
            val target = (position - seconds * 1000L).coerceAtLeast(0L)
            c.transportControls.seekTo(target)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "播放中"
        PlaybackState.STATE_PAUSED -> "已暂停"
        PlaybackState.STATE_STOPPED -> "已停止"
        PlaybackState.STATE_BUFFERING -> "缓冲中"
        PlaybackState.STATE_FAST_FORWARDING -> "快进中"
        PlaybackState.STATE_REWINDING -> "快退中"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "切下一首"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "切上一首"
        PlaybackState.STATE_NONE -> "空闲（App 未上报状态）"
        null -> "无状态"
        else -> "未知状态($state)"
    }

    companion object {
        /** 这些包名可能是「媒体控制中心」之类的壳会话，不是真正在放视频的 App */
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
    }
}

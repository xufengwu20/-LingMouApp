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

    // ------------------------------------------------------------------ 权限

    /** 用户是否已在系统设置里勾选本应用的通知使用权 */
    fun hasNotificationAccess(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (e: Exception) {
        false
    }

    private fun listenerComponent() = ComponentName(context, LingMouNotificationListener::class.java)

    // ------------------------------------------------------------------ 取会话

    /**
     * 原始会话列表。所有异常都在这里被吸收（未授权会抛 SecurityException），
     * 调用方不需要再 try，也永远不会因为媒体会话问题崩溃。
     */
    fun activeControllers(): List<MediaController> {
        val m = manager ?: return emptyList()
        if (!hasNotificationAccess()) return emptyList()
        return try {
            m.getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 排除本应用自己的会话，避免把指令发给自己 */
    private fun candidates(): List<MediaController> =
        activeControllers().filter { it.packageName != context.packageName }

    /**
     * 选一个「最可能正在放视频」的会话。
     *
     * 优先级：正在播放 > 处于可控制状态（暂停/缓冲/快进快退/停止） > 任意一个。
     * 最后那档是刻意保留的：不少国产 App 的 playbackState 上报不全（经常是 STATE_NONE），
     * 但 transportControls 依然有效，所以不能因为状态不标准就放弃它。
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
                summary = "未授予通知使用权，跨应用控制不可用",
                detail = "未授权"
            )
        }

        val list = candidates()
        if (list.isEmpty()) {
            return MediaSessionSnapshot(
                summary = "已授权，但当前没有活动媒体会话（先在 B 站/华为视频等播一个视频）",
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
     * 注册「活动会话变化」监听。授权状态一变就能立刻反映到界面上，
     * 不用等轮询。重复调用是安全的。
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
}

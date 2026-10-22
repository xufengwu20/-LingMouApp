package com.example.lingmouapp.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.lingmouapp.model.MediaAction
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
        if (!hasNotificationAccess()) {
            Log.w(TAG, "getActiveSessions 前置检查失败：通知使用权未授予（系统设置里未勾选灵眸）")
            return Query(emptyList(), "no_permission")
        }
        return try {
            val list = m.getActiveSessions(listenerComponent())
            val detail = list.joinToString("；") {
                "${it.packageName ?: "?"}=${stateName(it.playbackState?.state)}"
            }
            // 关键节点：把「拿到了哪些会话 + 监听服务是否真的连着」打出来
            if (list.isEmpty()) {
                Log.w(
                    TAG,
                    "getActiveSessions 返回空列表 ｜ 监听服务已连接=${MediaNotificationListener.connected}。" +
                        if (MediaNotificationListener.connected) {
                            "若确实在播放媒体仍为空：目标 App 未发布 MediaSession"
                        } else {
                            "监听服务没连着（重启/系统回收导致）→ 去通知使用权把灵眸关掉再打开"
                        }
                )
            } else {
                Log.i(
                    TAG,
                    "getActiveSessions 返回 ${list.size} 个会话：$detail ｜ 监听服务已连接=${MediaNotificationListener.connected}"
                )
            }
            Query(list, null)
        } catch (e: SecurityException) {
            lastQueryError = e.message ?: "SecurityException"
            Log.w(TAG, "getActiveSessions 抛 SecurityException：${e.message} —— 系统不认监听组件（把通知使用权关掉再打开一次）")
            Query(emptyList(), "security")
        } catch (e: Exception) {
            lastQueryError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "getActiveSessions 异常：${e.javaClass.simpleName} ${e.message}")
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
    private fun candidates(all: List<MediaController>): List<MediaController> =
        all.filter { it.packageName != context.packageName }
            .sortedBy { if (SYSTEM_PACKAGES.contains(it.packageName)) 1 else 0 }

    /**
     * 从**刚查回来的**会话列表里选一个「最可能正在放视频」的会话。
     *
     * 优先级：正在播放 > 处于可控制状态（暂停/缓冲/快进快退/停止） > 任意一个。
     * 最后那档是刻意保留的：不少国产 App 的 playbackState 上报不全
     * （B 站某些版本常常是 STATE_NONE），但 transportControls 依然有效，
     * 所以不能因为状态不标准就放弃它。
     *
     * 注意：本类**不缓存**任何 MediaController —— 每次调用都重新 getActiveSessions，
     * 因为外部 App 的会话随时可能失效（切歌、退出、被系统回收）。
     */
    private fun choosePrimary(all: List<MediaController>): MediaController? {
        val list = candidates(all)
        if (list.isEmpty()) return null
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        list.firstOrNull { isTransportable(it.playbackState?.state) }?.let { return it }
        return list.first()
    }

    /** 其他调用方（快照/探测）用：内部同样每次实时查询 */
    private fun primaryController(): MediaController? = choosePrimary(query().controllers)

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

        val list = candidates(result.controllers)
        if (list.isEmpty()) {
            return MediaSessionSnapshot(
                summary = "没有可下发的媒体会话（先在 B 站 / 华为视频里播放一个视频，再回到这里刷新）",
                sessionCount = 0,
                detail = "无"
            )
        }

        val chosen = choosePrimary(result.controllers)
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

    /**
     * 下发前把「拿到了哪些会话、目标是哪个包」写进 logcat（LingMouMedia），
     * 同时把每个控制动作的结果也写进去 —— 真机上「点了没反应」时，
     * 直接看 logcat 就能区分：没拿到会话 / 目标选错 / pause 调用被 App 忽略。
     */
    private fun describeForLog(): String {
        val result = query()
        val list = result.controllers
            .filter { it.packageName != context.packageName }
            .joinToString("；") { "${it.packageName ?: "?"}=${stateName(it.playbackState?.state)}" }
        return "会话列表(${list.length})：$list ｜ queryError=${result.error ?: "无"} ｜ 通知使用权=${hasNotificationAccess()}"
    }

    fun pause(): Boolean = dispatch(MediaAction.PAUSE, 0)

    fun play(): Boolean = dispatch(MediaAction.PLAY, 0)

    /** 回退：用 seekTo(当前位置 - seconds) 实现，比 ACTION_REWIND 兼容性好得多 */
    fun rewind(seconds: Int): Boolean = dispatch(MediaAction.REWIND, seconds)

    /**
     * 统一的下发流程 —— 刻意设计成「每次指令都全新获取，用完即弃」：
     *
     * 外部 App 的会话生命周期完全不受本应用控制，**旧 Controller 随时可能失效**，
     * 所以绝对不能在初始化时拿一次然后全局复用。这里的每一步都是本次调用内新查的：
     *   ① getActiveSessions 重新查（不缓存）
     *   ② 重新选主会话（不缓存）
     *   ③ transportControls 下发，try/catch 兜异常
     *   ④ finally：丢弃引用 —— MediaController 没有 close API，本类也不注册
     *      callback，所以「释放」= 不持有引用；同时本类**没有任何** isProcessing/
     *      isPaused 之类的跨指令状态锁，不存在「锁忘了重置导致第二次指令被吞」的问题。
     */
    private fun dispatch(action: MediaAction, seconds: Int): Boolean {
        // ① 每次指令都重新获取活跃会话
        val result = query()
        val controllers = result.controllers
        Log.i(
            TAG,
            "执行 ${action.label} 前，getActiveSessions 返回 ${controllers.size} 个会话" +
                "（通知使用权=${hasNotificationAccess()}，监听已连接=${MediaNotificationListener.connected}）"
        )

        // ② 重新选主会话
        val c = choosePrimary(controllers)
        if (c == null) {
            Log.w(TAG, "${action.label}：没有可用会话 —— ${describeForLog()}")
            return false
        }
        val pkg = c.packageName
        val beforeState = stateName(c.playbackState?.state)
        val beforePosition = c.playbackState?.position

        // ③④ 下发 + 无论成败都在 finally 丢引用
        return try {
            when (action) {
                MediaAction.PAUSE -> {
                    c.transportControls.pause()
                    Log.i(TAG, "已向 $pkg 下发 pause（下发前 state=$beforeState）")
                }
                MediaAction.PLAY -> {
                    c.transportControls.play()
                    Log.i(TAG, "已向 $pkg 下发 play（下发前 state=$beforeState）")
                }
                MediaAction.REWIND -> {
                    val target = ((beforePosition ?: 0L) - seconds * 1000L).coerceAtLeast(0L)
                    c.transportControls.seekTo(target)
                    Log.i(TAG, "已向 $pkg 下发 seekTo($target)（原位置 $beforePosition）")
                }
            }
            verifyAfterDispatch(pkg, action.label, beforeState, beforePosition)
            true
        } catch (e: Exception) {
            Log.w(TAG, "向 $pkg 下发 ${action.label} 异常（未被吞掉，这里打印）：${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            Log.i(TAG, "${action.label} 流程结束：Controller 引用已丢弃（不缓存，下次指令重新获取）")
        }
    }

    /**
     * 下发后校验：TransportControls 的调用是异步 fire-and-forget，**不会**返回
     * 「成功/失败」；目标 App 也完全可能静默忽略。唯一诚实的判断方式是稍等片刻
     * 再查一次它的 playbackState，看状态/位置是否真的变了。
     */
    private fun verifyAfterDispatch(
        pkg: String?,
        actionName: String,
        beforeState: String,
        beforePosition: Long?
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            val after = query().controllers.firstOrNull { it.packageName == pkg }
            val afterState = stateName(after?.playbackState?.state)
            val afterPosition = after?.playbackState?.position
            val stateChanged = afterState != beforeState
            val positionChanged =
                beforePosition != null && afterPosition != null && afterPosition != beforePosition
            Log.i(
                TAG,
                "[下发后校验] $pkg：$actionName 前=[$beforeState @ $beforePosition] 后=[$afterState @ $afterPosition] " +
                    if (stateChanged || positionChanged) {
                        "→ 状态/位置已变化 ✓（指令生效）"
                    } else {
                        "→ 状态未变化 ✗（目标 App 忽略了指令，或会话已消失）"
                    }
            )
        }, DISPATCH_VERIFY_DELAY_MS)
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
        /** logcat 过滤：adb logcat -s LingMouMedia */
        const val TAG = "LingMouMedia"

        /** 下发后隔多久复查目标状态（TransportControls 是异步的） */
        private const val DISPATCH_VERIFY_DELAY_MS = 800L

        /** 这些包名可能是「媒体控制中心」之类的壳会话，不是真正在放视频的 App */
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")
    }
}

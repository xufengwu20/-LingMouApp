package com.example.lingmouapp.service.nlp

import com.example.lingmouapp.model.CommandSelfTest
import com.example.lingmouapp.model.MediaAction
import com.example.lingmouapp.model.SpeechCommand

/**
 * 本地指令路由：把一句文本翻译成「要执行什么动作」。
 *
 * ⚠️ 这个文件是**纯 Kotlin**：不 import 任何 Android / Ktor / BuildConfig 类型，
 * 因此它在结构上就不可能依赖 API Key —— 这不是靠约定或注释保证的，
 * 是编译器保证的。想让它联网，必须先改 import，一眼就能看出来。
 *
 * 手动模拟输入、快捷按钮、语音识别结果，最终都汇聚到 [route]，
 * 所以三者行为完全一致，「配不配 Key」对它们没有任何影响。
 */
object LocalCommandRouter {

    /** 路由结果：一个动作，还没被执行 */
    sealed interface PlannedAction {
        val reason: String
        fun describe(): String

        data class Media(
            val action: MediaAction,
            val seconds: Int,
            override val reason: String
        ) : PlannedAction {
            override fun describe(): String = when (action) {
                MediaAction.PAUSE -> "暂停播放"
                MediaAction.PLAY -> "继续播放"
                MediaAction.REWIND -> "回退 $seconds 秒"
            }
        }

        data class ScheduleTimer(
            val durationMs: Long,
            val label: String,
            override val reason: String
        ) : PlannedAction {
            override fun describe(): String = "设置定时提醒：$label"
        }

        data class Nothing(override val reason: String) : PlannedAction {
            override fun describe(): String = "未匹配到可执行指令"
        }
    }

    /**
     * @param source 来源标记，只用于日志（"手动模拟" / "语音识别" / "自检"）
     */
    fun route(raw: String, source: String): List<PlannedAction> {
        val reason = "$source：$raw"
        return when (val command = SpeechCommandParser.parse(raw)) {
            is SpeechCommand.Pause ->
                listOf(PlannedAction.Media(MediaAction.PAUSE, 0, reason))
            is SpeechCommand.Resume ->
                listOf(PlannedAction.Media(MediaAction.PLAY, 0, reason))
            is SpeechCommand.Rewind ->
                listOf(PlannedAction.Media(MediaAction.REWIND, command.seconds, reason))
            is SpeechCommand.SetTimer ->
                listOf(PlannedAction.ScheduleTimer(command.durationMs, command.label, reason))
            is SpeechCommand.Unknown ->
                listOf(PlannedAction.Nothing(reason))
        }
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 动作级自检：验证「文本 → 要执行的动作」。
     *
     * 比解析级自检更接近真实闭环 —— 它检查的是**最终会调用哪个控制方法**，
     * 而不是中间的命令对象。全部走本地计算，不碰麦克风、不联网、不看 API Key。
     */
    fun selfTest(): List<CommandSelfTest> = SELF_TEST_CASES.map { case ->
        val actions = route(case.input, "自检")
        val actual = actions.joinToString("；") { it.describe() }
        CommandSelfTest(
            input = case.input,
            expected = case.human,
            actual = actual,
            passed = canonical(actions) == case.canonical
        )
    }

    private fun canonical(actions: List<PlannedAction>): String =
        actions.joinToString("+") { action ->
            when (action) {
                is PlannedAction.Media -> when (action.action) {
                    MediaAction.PAUSE -> "Media:PAUSE"
                    MediaAction.PLAY -> "Media:PLAY"
                    MediaAction.REWIND -> "Media:REWIND:${action.seconds}"
                }
                is PlannedAction.ScheduleTimer -> "Timer:${action.durationMs}"
                is PlannedAction.Nothing -> "Nothing"
            }
        }

    private data class SelfTestCase(
        val input: String,
        val canonical: String,
        val human: String
    )

    private val SELF_TEST_CASES = listOf(
        // 你要求的 5 条必测指令
        SelfTestCase("暂停", "Media:PAUSE", "暂停播放"),
        SelfTestCase("继续", "Media:PLAY", "继续播放"),
        // 「回退」优先级必须高于时间解析，否则会变成 10 秒定时
        SelfTestCase("回退10秒", "Media:REWIND:10", "回退 10 秒"),
        SelfTestCase("15分钟后提醒我", "Timer:900000", "设置定时提醒：15分钟"),
        SelfTestCase("半小时", "Timer:1800000", "设置定时提醒：30分钟"),
        // 边界用例
        SelfTestCase("停一下", "Media:PAUSE", "暂停播放"),
        SelfTestCase("播放", "Media:PLAY", "继续播放"),
        SelfTestCase("倒退五秒", "Media:REWIND:5", "回退 5 秒"),
        // 「一个半小时」必须走专门规则，否则会和「半小时」重复计数
        SelfTestCase("一个半小时后叫我", "Timer:5400000", "设置定时提醒：1小时30分钟"),
        SelfTestCase("一小时后提醒", "Timer:3600000", "设置定时提醒：1小时"),
        SelfTestCase("今天天气不错", "Nothing", "未匹配到可执行指令")
    )
}

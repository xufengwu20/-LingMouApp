package com.example.lingmouapp.service.nlp

import com.example.lingmouapp.model.CommandSelfTest
import com.example.lingmouapp.model.SpeechCommand

/**
 * 语音指令解析：当前是关键词匹配的简单实现，能跑通逻辑即可。
 *
 * 判定顺序很重要：
 *   1. 回退 —— 否则「回退10秒」会先被时间解析捕获，变成 10 秒定时
 *   2. 时间表达式 —— 出现「15分钟」「半小时」就当作设定时提醒
 *   3. 暂停 / 继续
 *   4. 只说了「提醒」但没给时间 → 默认 10 分钟
 */
object SpeechCommandParser {

    const val DEFAULT_REWIND_SECONDS = 10

    private val rewindKeywords = listOf("回退", "倒退", "后退", "倒回", "往回", "rewind", "go back")
    private val pauseKeywords = listOf("暂停", "停一下", "停一停", "先停", "别放", "停下", "pause")
    private val resumeKeywords = listOf("继续", "接着", "恢复", "播放", "resume", "continue", "play")
    private val timerKeywords = listOf("提醒", "定时", "计时", "闹钟", "叫我", "timer", "alarm")

    fun parse(raw: String): SpeechCommand {
        val text = raw.trim()
        if (text.isEmpty()) return SpeechCommand.Unknown(raw)
        val lower = text.lowercase()

        if (rewindKeywords.any { lower.contains(it) }) {
            val seconds = TimeExpressionParser.parseSeconds(text, DEFAULT_REWIND_SECONDS)
            return SpeechCommand.Rewind(seconds, text)
        }

        val duration = TimeExpressionParser.parseMillis(text)
        if (duration != null) {
            return SpeechCommand.SetTimer(duration, TimeExpressionParser.describe(duration), text)
        }

        if (pauseKeywords.any { lower.contains(it) }) return SpeechCommand.Pause(text)
        if (resumeKeywords.any { lower.contains(it) }) return SpeechCommand.Resume(text)

        if (timerKeywords.any { lower.contains(it) }) {
            val fallback = 10 * 60_000L
            return SpeechCommand.SetTimer(fallback, "10分钟", text)
        }

        return SpeechCommand.Unknown(text)
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 本地指令自检。
     *
     * 这些用例走的是**和真实语音识别完全相同**的入口 [parse]，
     * 不碰麦克风、不碰网络、不使用任何 API Key。
     * 界面上跑一遍全绿，就说明「送入指令 → 触发控制行为」这条链路是通的，
     * 与语音识别后端是否配置无关。
     */
    fun selfTest(): List<CommandSelfTest> = SELF_TEST_CASES.map { case ->
        val parsed = parse(case.input)
        CommandSelfTest(
            input = case.input,
            expected = case.human,
            actual = parsed.describe(),
            passed = canonical(parsed) == case.canonical
        )
    }

    private fun canonical(command: SpeechCommand): String = when (command) {
        is SpeechCommand.Pause -> "Pause"
        is SpeechCommand.Resume -> "Resume"
        is SpeechCommand.Rewind -> "Rewind:${command.seconds}"
        is SpeechCommand.SetTimer -> "SetTimer:${command.durationMs}"
        is SpeechCommand.Unknown -> "Unknown"
    }

    private data class SelfTestCase(
        val input: String,
        val canonical: String,
        val human: String
    )

    private val SELF_TEST_CASES = listOf(
        SelfTestCase("暂停", "Pause", "暂停播放"),
        SelfTestCase("停一下", "Pause", "暂停播放"),
        SelfTestCase("继续", "Resume", "继续播放"),
        SelfTestCase("播放", "Resume", "继续播放"),
        // 「回退」优先级高于时间解析，否则这里会变成 10 秒定时
        SelfTestCase("回退10秒", "Rewind:10", "回退 10 秒"),
        SelfTestCase("倒退五秒", "Rewind:5", "回退 5 秒"),
        SelfTestCase("15分钟后提醒我", "SetTimer:900000", "定时提醒：15分钟（900 秒后）"),
        SelfTestCase("半小时", "SetTimer:1800000", "定时提醒：30分钟（1800 秒后）"),
        // 「一个半小时」必须走专门的规则，否则会和「半小时」重复计数
        SelfTestCase("一个半小时后叫我", "SetTimer:5400000", "定时提醒：1小时30分钟（5400 秒后）"),
        SelfTestCase("一小时后提醒", "SetTimer:3600000", "定时提醒：1小时（3600 秒后）"),
        SelfTestCase("今天天气不错", "Unknown", "未匹配到指令")
    )
}

package com.example.lingmouapp.service.nlp

import com.example.lingmouapp.model.SpeechCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指令解析的 JVM 单元测试。
 *
 * 这一层完全不碰 Android API、不碰麦克风、不碰网络，也不使用任何 API Key，
 * 所以它能直接在电脑上跑（./gradlew testDebugUnitTest），
 * 是证明「送入指令 → 触发控制行为」独立于语音识别后端的最硬证据。
 */
class SpeechCommandParserTest {

    @Test
    fun selfTestAllPass() {
        val results = SpeechCommandParser.selfTest()
        val failed = results.filterNot { it.passed }
        assertTrue(
            "以下用例未通过：\n" + failed.joinToString("\n") {
                "  「${it.input}」期望 ${it.expected}，实际 ${it.actual}"
            },
            failed.isEmpty()
        )
        assertEquals(11, results.size)
    }

    @Test
    fun rewindTakesPriorityOverTimer() {
        // 「回退10秒」里含时间信息，但必须解析成回退而不是 10 秒定时
        val command = SpeechCommandParser.parse("回退10秒")
        assertTrue("实际类型：${command.javaClass.simpleName}", command is SpeechCommand.Rewind)
        assertEquals(10, (command as SpeechCommand.Rewind).seconds)
    }

    @Test
    fun chineseNumeralSecondsAreParsed() {
        val command = SpeechCommandParser.parse("倒退五秒")
        assertTrue(command is SpeechCommand.Rewind)
        assertEquals(5, (command as SpeechCommand.Rewind).seconds)
    }

    @Test
    fun rewindDefaultsToTenSecondsWhenNoNumberGiven() {
        val command = SpeechCommandParser.parse("回退一下")
        assertTrue(command is SpeechCommand.Rewind)
        assertEquals(SpeechCommandParser.DEFAULT_REWIND_SECONDS, (command as SpeechCommand.Rewind).seconds)
    }

    @Test
    fun halfHourIsThirtyMinutes() {
        assertEquals(30 * 60_000L, timerOf("半小时"))
        assertEquals(30 * 60_000L, timerOf("半个小时"))
    }

    @Test
    fun oneAndHalfHourIsNinetyMinutes() {
        // 「一个半小时」如果连同「半小时」规则一起命中，就会重复计数成 60 分钟
        assertEquals(90 * 60_000L, timerOf("一个半小时后叫我"))
    }

    @Test
    fun compoundDurationIsSummed() {
        assertEquals(90 * 60_000L, timerOf("1小时30分钟后提醒我"))
    }

    @Test
    fun chineseNumeralMinutesAreParsed() {
        assertEquals(15 * 60_000L, timerOf("十五分钟后提醒我"))
        assertEquals(25 * 60_000L, timerOf("二十五分钟"))
    }

    @Test
    fun pauseBeatsResumeWhenBothKeywordsPresent() {
        assertTrue(SpeechCommandParser.parse("暂停播放") is SpeechCommand.Pause)
    }

    @Test
    fun unmatchedSentenceStaysUnknown() {
        assertTrue(SpeechCommandParser.parse("今天天气不错") is SpeechCommand.Unknown)
    }

    @Test
    fun timerKeywordWithoutTimeFallsBackToTenMinutes() {
        assertEquals(10 * 60_000L, timerOf("提醒我"))
    }

    private fun timerOf(raw: String): Long {
        val command = SpeechCommandParser.parse(raw)
        assertTrue("「$raw」应为定时提醒，实际：${command.describe()}", command is SpeechCommand.SetTimer)
        return (command as SpeechCommand.SetTimer).durationMs
    }
}

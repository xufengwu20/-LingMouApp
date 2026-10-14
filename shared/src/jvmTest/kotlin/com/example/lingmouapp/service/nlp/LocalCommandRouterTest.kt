package com.example.lingmouapp.service.nlp

import com.example.lingmouapp.model.MediaAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文本 → 控制动作」的闭环测试。
 *
 * 这一层完全跑在 JVM 上：不需要真机、不需要麦克风、不需要网络、**不需要 API Key**。
 * 它全绿，就等于证明了「送入指令 → 触发控制动作」这条链路是通的。
 *
 * 之所以能这么测，是因为 [LocalCommandRouter] 被刻意做成纯 Kotlin——
 * 不 import 任何 Android / Ktor / BuildConfig 类型，从结构上就不可能依赖 API Key。
 */
class LocalCommandRouterTest {

    @Test
    fun selfTestAllPass() {
        val results = LocalCommandRouter.selfTest()
        val failed = results.filterNot { it.passed }
        assertTrue(
            "以下用例未通过：\n" + failed.joinToString("\n") {
                "  「${it.input}」期望 ${it.expected}，实际 ${it.actual}"
            },
            failed.isEmpty()
        )
        assertEquals(11, results.size)
    }

    // ---- 用户明确要求验证的 5 条指令，逐条断言最终动作 ----

    @Test
    fun pauseMapsToPauseAction() = assertMedia("暂停", MediaAction.PAUSE, 0)

    @Test
    fun resumeMapsToPlayAction() = assertMedia("继续", MediaAction.PLAY, 0)

    @Test
    fun rewindMapsToRewindTenSeconds() = assertMedia("回退10秒", MediaAction.REWIND, 10)

    @Test
    fun fifteenMinutesMapsToTimer() = assertTimer("15分钟后提醒我", 15 * 60_000L)

    @Test
    fun halfHourMapsToTimer() = assertTimer("半小时", 30 * 60_000L)

    // ---- 结构与边界 ----

    @Test
    fun routeIsPureAndRepeatable() {
        // 纯函数：同样的输入永远得到同样的结果。
        // 没有任何全局开关（比如 API Key 是否配置）能影响它。
        assertEquals(LocalCommandRouter.selfTest(), LocalCommandRouter.selfTest())
    }

    @Test
    fun unknownTextPlansNothing() {
        val actions = LocalCommandRouter.route("今天天气不错", "test")
        assertEquals(1, actions.size)
        assertTrue(
            "未匹配时应返回 Nothing，实际：${actions.first().describe()}",
            actions.first() is LocalCommandRouter.PlannedAction.Nothing
        )
    }

    @Test
    fun sourceOnlyAffectsTheReasonText() {
        // source 只用来写日志，不应该改变动作本身
        val a = LocalCommandRouter.route("暂停", "手动模拟")
        val b = LocalCommandRouter.route("暂停", "语音识别")
        assertEquals(a.map { it.describe() }, b.map { it.describe() })
    }

    private fun assertMedia(raw: String, action: MediaAction, seconds: Int) {
        val actions = LocalCommandRouter.route(raw, "test")
        assertEquals(1, actions.size)
        val planned = actions.first()
        assertTrue(
            "「$raw」应为媒体动作，实际：${planned.describe()}",
            planned is LocalCommandRouter.PlannedAction.Media
        )
        planned as LocalCommandRouter.PlannedAction.Media
        assertEquals(action, planned.action)
        assertEquals(seconds.toLong(), planned.seconds.toLong())
    }

    private fun assertTimer(raw: String, durationMs: Long) {
        val actions = LocalCommandRouter.route(raw, "test")
        assertEquals(1, actions.size)
        val planned = actions.first()
        assertTrue(
            "「$raw」应为定时提醒，实际：${planned.describe()}",
            planned is LocalCommandRouter.PlannedAction.ScheduleTimer
        )
        planned as LocalCommandRouter.PlannedAction.ScheduleTimer
        assertEquals(durationMs, planned.durationMs)
    }
}

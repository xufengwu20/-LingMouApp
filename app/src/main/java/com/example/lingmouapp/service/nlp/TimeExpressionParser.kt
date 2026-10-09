package com.example.lingmouapp.service.nlp

/**
 * 从一句话里抽时间信息。目标是「够用」，不是完美 NLU。
 *
 * 支持：15分钟 / 15分 / 半小时 / 半个小时 / 一个小时 / 两小时 / 1小时30分钟 /
 *      一个半小时 / 20秒 / 15min / 1hour ...
 */
object TimeExpressionParser {

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val MAX_DURATION = 24 * HOUR

    private const val NUM = "[0-9]+|[零〇一二两三四五六七八九十百]+"

    /** 「一个半小时」必须最先处理，否则会同时命中午小时规则造成重复计数 */
    private val hourAndHalfRegex = Regex("(一|1|两|2)\\s*个?\\s*半\\s*个?\\s*(小时|钟头)")
    private val halfHourRegex = Regex("半\\s*个?\\s*(小时|钟头)")
    private val hourRegex = Regex("($NUM)\\s*个?\\s*(小时|钟头|hour|h)")
    private val minuteRegex = Regex("($NUM)\\s*(分钟|分|min)")
    private val secondRegex = Regex("($NUM)\\s*(秒钟|秒|sec)")

    fun parseMillis(raw: String): Long? {
        val text = raw.replace(" ", "").lowercase()
        if (text.isEmpty()) return null

        if (hourAndHalfRegex.containsMatchIn(text)) {
            return (90 * MINUTE).coerceAtMost(MAX_DURATION)
        }

        var total = 0L
        var found = false

        if (halfHourRegex.containsMatchIn(text)) {
            total += 30 * MINUTE
            found = true
        }
        hourRegex.findAll(text).forEach { match ->
            val n = ChineseNumberParser.parse(match.groupValues[1]) ?: 1
            total += n * HOUR
            found = true
        }
        minuteRegex.findAll(text).forEach { match ->
            val n = ChineseNumberParser.parse(match.groupValues[1]) ?: 1
            total += n * MINUTE
            found = true
        }
        secondRegex.findAll(text).forEach { match ->
            val n = ChineseNumberParser.parse(match.groupValues[1]) ?: 1
            total += n * SECOND
            found = true
        }

        if (!found || total <= 0L) return null
        return total.coerceAtMost(MAX_DURATION)
    }

    /**
     * 专门给「回退 X 秒」用：只找秒数，找不到就用调用方给的默认值。
     * 注意必须和 parseMillis 分开，否则「回退10秒」会被当成 10 秒定时。
     */
    fun parseSeconds(raw: String, defaultSeconds: Int): Int {
        val text = raw.replace(" ", "")
        val match = secondRegex.find(text) ?: return defaultSeconds
        val n = ChineseNumberParser.parse(match.groupValues[1]) ?: return defaultSeconds
        return n.coerceIn(1, 300)
    }

    fun describe(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "${h}小时"
        if (m > 0) parts += "${m}分钟"
        if (s > 0) parts += "${s}秒"
        return if (parts.isEmpty()) "0秒" else parts.joinToString("")
    }
}

package com.example.lingmouapp.service.nlp

/**
 * 中文数字 → 整数。覆盖「二十五分钟」「一百二十分钟」这类说法，同时兼容阿拉伯数字。
 * 只做 0~999 的范围，足够表达任何合理的定时时长。
 */
object ChineseNumberParser {

    private val digitMap: Map<Char, Int> = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '两' to 2, '贰' to 2,
        '三' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9
    )

    fun parse(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }

        var total = 0
        var current = 0
        var any = false

        for (ch in s) {
            val digit = digitMap[ch]
            when {
                digit != null -> {
                    current = digit
                    any = true
                }
                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                    any = true
                }
                ch == '百' -> {
                    total += (if (current == 0) 1 else current) * 100
                    current = 0
                    any = true
                }
                else -> return if (any) total + current else null
            }
        }
        return if (any) total + current else null
    }
}

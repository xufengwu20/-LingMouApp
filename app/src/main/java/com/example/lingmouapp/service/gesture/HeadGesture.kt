package com.example.lingmouapp.service.gesture

/**
 * 头部动作分类结果。
 *
 * [code] 就是需求里约定的字符串："nod" / "shake" / "none"。
 */
enum class HeadGesture(val code: String, val label: String) {
    NOD("nod", "点头"),
    SHAKE("shake", "摇头"),
    NONE("none", "无");

    companion object {
        fun fromCode(code: String): HeadGesture =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: NONE
    }
}

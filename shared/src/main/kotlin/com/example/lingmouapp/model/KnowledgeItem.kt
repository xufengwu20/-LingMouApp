package com.example.lingmouapp.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 知识库的 UI 模型。
 *
 * 刻意不让 UI 直接使用 SQLDelight 生成的数据类：
 * 一来界面不需要关心数据库列的细节，二来以后换存储实现时 UI 不用改。
 */
data class NoteItem(
    val id: Long,
    val title: String,
    val content: String,
    val mode: String,
    val createdAt: Long
) {
    val createdText: String get() = formatShortTime(createdAt)
    val modeLabel: String get() = if (mode.equals("COOKING", true)) "烹饪" else "专注"
}

data class RecipeItem(
    val id: Long,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val createdAt: Long
) {
    val createdText: String get() = formatShortTime(createdAt)
}

private val shortTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun formatShortTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(shortTimeFormat)

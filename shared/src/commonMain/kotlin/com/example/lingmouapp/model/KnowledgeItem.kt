package com.example.lingmouapp.model

/**
 * 知识库的 UI 模型。
 *
 * 刻意不让 UI 直接使用 SQLDelight 生成的数据类：
 * 一来界面不需要关心数据库列的细节，二来以后换存储实现时 UI 不用改。
 *
 * 注意 [NoteItem.createdText] / [RecipeItem.createdText] 是**构造参数**而不是计算属性：
 * 格式化时间要 java.time（Android/JVM）或 NSDateFormatter（Apple），
 * 这些在 KMP 的 commonMain 里都拿不到，所以由平台侧格式化好再填进来。
 */
data class NoteItem(
    val id: Long,
    val title: String,
    val content: String,
    val mode: String,
    val createdAt: Long,
    val createdText: String = ""
) {
    val modeLabel: String get() = if (mode.equals("COOKING", true)) "烹饪" else "专注"
}

data class RecipeItem(
    val id: Long,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val createdAt: Long,
    val createdText: String = ""
)

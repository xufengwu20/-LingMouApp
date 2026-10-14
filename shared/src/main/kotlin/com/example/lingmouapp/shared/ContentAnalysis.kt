package com.example.lingmouapp.shared

/** LLM 对一段视频文字稿的总结结果。 */
data class VideoSummary(
    val title: String,
    val summary: String,
    val keyPoints: List<String> = emptyList()
)

/** 菜谱里的一步。 */
data class RecipeStepDraft(
    val instruction: String,
    val durationHint: String = ""
)

/** LLM 从文字稿里抽取出的菜谱（尚未落库，没有 id / 时间戳）。 */
data class RecipeDraft(
    val title: String,
    val summary: String,
    val steps: List<RecipeStepDraft> = emptyList()
)

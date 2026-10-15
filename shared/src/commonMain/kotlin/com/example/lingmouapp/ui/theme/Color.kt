package com.example.lingmouapp.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// 统一配色（面向最终用户的版本）
//   主色：深蓝 #1A3A5C
//   背景：白色
//   强调：橙色 #E67E22
// ============================================================

val NavyDeep = Color(0xFF1A3A5C)   // 深蓝 #1A3A5C —— 主色
val Navy = Color(0xFF24466F)       // 深蓝亮一档（辅助/按下）
val Orange = Color(0xFFE67E22)     // 橙色 #E67E22 —— 强调色
val OrangeDeep = Color(0xFFB65C13) // 橙色深一档

val Ink = Color(0xFF1F2937)        // 正文
val InkSoft = Color(0xFF6B7280)    // 次要文字
val Rose = Color(0xFFD64545)       // 错误/危险
val Mint = Color(0xFF38A169)       // 成功/正向

val PageLight = Color(0xFFFFFFFF)  // 白色背景
val SurfaceLight = Color(0xFFF7F9FB)
val PageDark = Color(0xFF0D1A2B)   // 深色模式（深蓝基调）
val SurfaceDark = Color(0xFF16293F)

// ---- 旧版青绿/琥珀名称的兼容别名（防止有遗漏引用处编译失败） ----
val Teal = Navy
val TealDeep = NavyDeep
val Amber = Orange

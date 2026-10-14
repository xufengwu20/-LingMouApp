package com.example.lingmouapp.model

import com.example.lingmouapp.shared.currentTimeMillis

/**
 * 一条已排定的定时提醒（只存在于内存中，不做持久化）。
 *
 * createdAtMillis 走 [currentTimeMillis]（expect/actual）而不是直接写
 * `System.currentTimeMillis()`：后者是 JVM API。放在 commonMain 里之所以能编译过，
 * 只是因为「两个 target 恰好都是 JVM 时 Kotlin 会跳过公共元数据编译」，
 * 一旦将来加了非 JVM target 就会当场失败。
 */
data class Reminder(
    val id: Int,
    val label: String,
    val triggerAtMillis: Long,
    val exact: Boolean,
    val createdAtMillis: Long = currentTimeMillis()
)

package com.example.lingmouapp.model

/** 一条已排定的定时提醒（只存在于内存中，不做持久化）。 */
data class Reminder(
    val id: Int,
    val label: String,
    val triggerAtMillis: Long,
    val exact: Boolean,
    val createdAtMillis: Long = System.currentTimeMillis()
)

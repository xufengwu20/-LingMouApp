package com.example.lingmouapp.shared

/**
 * commonMain 里没有 `System.currentTimeMillis()`（那是 JVM API），
 * 也没有 `java.time`。凡是这类平台能力都从这里走 expect/actual。
 *
 * 之所以不做成 `jvmCommonMain` 中间源集：Android 与 JVM 在 Kotlin 的默认
 * 层级模板里是两个独立分支，中间源集要额外配置；这里只有一两个函数，
 * 直接在 androidMain / jvmMain 各写一份 actual 更直观、更不容易出构建问题。
 */
expect fun currentTimeMillis(): Long

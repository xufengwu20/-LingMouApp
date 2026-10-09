package com.example.lingmouapp.model

/** 一条本地指令自检结果。用来证明指令链路完全不依赖麦克风与云端 API Key。 */
data class CommandSelfTest(
    val input: String,
    val expected: String,
    val actual: String,
    val passed: Boolean
)

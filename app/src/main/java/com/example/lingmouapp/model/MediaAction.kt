package com.example.lingmouapp.model

/** 对外发出的媒体控制指令。 */
enum class MediaAction(val label: String) {
    PAUSE("暂停"),
    PLAY("播放"),
    REWIND("回退")
}

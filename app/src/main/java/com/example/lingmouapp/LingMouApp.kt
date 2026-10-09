package com.example.lingmouapp

import android.app.Application
import com.example.lingmouapp.service.LingMouEngine

class LingMouApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 尽早建立通知渠道、媒体会话控制器与定时器；
        // 即使 App 还没进入任何模式，跨应用媒体控制也已经可用。
        LingMouEngine.init(this)
    }
}

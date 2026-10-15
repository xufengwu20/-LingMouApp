package com.example.lingmouapp.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.lingmouapp.ui.KnowledgeScreen
import com.example.lingmouapp.ui.MainScreen
import com.example.lingmouapp.ui.theme.LingMouTheme
import java.io.File

/** 启动自检的输出前缀，方便从日志里 grep */
private const val PROBE = "[LINGMOU-PROBE]"

/**
 * 确保 `java.io.tmpdir` 是一个**真的**能创建文件的目录。
 *
 * 为什么需要这个：Compose Desktop 的渲染引擎 skiko 在初始化时会把原生库解压到
 * `java.io.tmpdir`。如果那个目录实际不可写，App 会在 `application { }` 里
 * 直接抛 `java.io.IOException` 退出，连窗口都开不出来 —— 而且报错信息是
 * 「系统找不到指定的路径」，很容易被误判成路径拼错。
 *
 * 注意这里必须**真的试写一次**，不能只看 `File.canWrite()`：
 * 在受限沙箱里 `canWrite()` 会返回 true，但实际创建文件仍然被拒绝。
 *
 * 退路选在 `user.dir`（启动目录）下的 `.lingmou-tmp`：它通常就在用户自己的工作目录里，
 * 也是权限受限环境下最可能可写的位置。
 */
private fun ensureUsableTempDir() {
    val current = System.getProperty("java.io.tmpdir")?.let { File(it) }
    if (current != null && canReallyCreateFileIn(current)) return

    val fallback = File(System.getProperty("user.dir") ?: ".", ".lingmou-tmp")
    val prepared = (fallback.isDirectory || fallback.mkdirs()) && canReallyCreateFileIn(fallback)
    if (prepared) {
        System.setProperty("java.io.tmpdir", fallback.absolutePath)
        println("$PROBE 系统临时目录不可写，已改用 ${fallback.absolutePath}")
    } else {
        println("$PROBE 警告：系统临时目录与备用目录都不可写，skiko 可能无法启动")
    }
}

/** 真刀真枪试一次创建+删除，而不是看访问位 */
private fun canReallyCreateFileIn(dir: File): Boolean = try {
    File.createTempFile("lingmou-probe", ".tmp", dir).delete()
    true
} catch (e: Throwable) {
    false
}

/**
 * Windows 桌面端入口。
 *
 * 界面上看到的**就是 :shared/commonMain 里那套 Compose 组件**——
 * 与 Android 端逐字相同的那份源码，没有任何桌面专用副本。
 * 差异只通过两处注入体现：
 *   1. `state.capabilities`（决定哪里显示「当前平台暂不支持」）
 *   2. `previewSlot`（桌面端传 null，相机区域就自动变成不支持提示）
 */
fun main() {
    // 必须在任何 Compose / skiko 类加载之前修好临时目录：
    // skiko 启动时要用 File.createTempFile 把原生库解压到 java.io.tmpdir。
    ensureUsableTempDir()

    run {
        val tmp = System.getProperty("java.io.tmpdir")
        val dir = java.io.File(tmp)
        println("$PROBE java.io.tmpdir=$tmp")
        println("$PROBE tmpExists=${dir.exists()} tmpIsDirectory=${dir.isDirectory}")
    }

    DesktopEngine.init()

    Runtime.getRuntime().addShutdownHook(Thread { DesktopEngine.shutdown() })

    application {
        val state by DesktopEngine.state.collectAsState()

        Window(
            onCloseRequest = ::exitApplication,
            title = "灵眸 LingMouApp · Windows 桌面端",
            state = rememberWindowState(size = DpSize(520.dp, 940.dp))
        ) {
            LingMouTheme {
                // ------------------------------------------------------------
                // 启动自检
                //
                // 为什么要有这段：开发这个桌面端的环境里没有「用眼睛看窗口」的能力，
                // 所以让程序自己把「UI 是否真的组合完成」「本地指令闭环是否真的触发」
                // 打成 stdout 证据。这不是调试残留 —— 它是一个可自动化的冒烟测试，
                // 关掉它只是删掉几行 println 的事。
                // ------------------------------------------------------------
                LaunchedEffect(Unit) {
                    val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    println("$PROBE headless=${java.awt.GraphicsEnvironment.isHeadless()}")
                    println("$PROBE screenCount=${ge.screenDevices.size}")
                    ge.screenDevices.forEachIndexed { index, device ->
                        println("$PROBE screen[$index] bounds=${device.defaultConfiguration.bounds}")
                    }
                    println("$PROBE UI_COMPOSED_OK windowTitle=「${window.title}」")
                    println("$PROBE windowSize=${window.size}")

                    val bootState = DesktopEngine.state.value
                    println("$PROBE platform=${bootState.capabilities.platformName}")
                    println("$PROBE mode=${bootState.mode.label} databaseReady=${bootState.databaseReady}")
                    println("$PROBE unsupportedCount=${bootState.capabilities.unsupported.size}")

                    // 本地指令闭环冒烟：完全不依赖麦克风与 API Key
                    DesktopEngine.runSelfTest()
                    DesktopEngine.runManualCommand("暂停一下")
                    DesktopEngine.runManualCommand("十分钟后提醒我测一下")
                    DesktopEngine.runManualCommand("回退五秒")

                    val after = DesktopEngine.state.value
                    println("$PROBE selfTestPassed=${after.commandSelfTests.count { it.passed }}/${after.commandSelfTests.size}")
                    println("$PROBE lastCommand=${after.lastCommand}")
                    println("$PROBE playerPlaying=${after.player.isPlaying} position=${after.player.positionMs}")
                    println("$PROBE reminders=${after.reminders.size}")
                    println("$PROBE noteCount=${after.notes.size} recipeCount=${after.recipes.size}")
                    println("$PROBE PROBE_DONE")
                }

                if (state.showKnowledgeBase) {
                    KnowledgeScreen(
                        state = state,
                        onBack = { DesktopEngine.setShowKnowledgeBase(false) },
                        onRefresh = { DesktopEngine.refreshKnowledge() },
                        onDeleteNote = { DesktopEngine.deleteNote(it) },
                        onDeleteRecipe = { DesktopEngine.deleteRecipe(it) }
                    )
                } else {
                    MainScreen(
                        state = state,
                        // 桌面端没有人脸检测 → 相机区域自动显示「当前平台暂不支持人脸检测」
                        previewSlot = null,
                        onSelectMode = { DesktopEngine.setMode(it) },
                        onOpenKnowledgeBase = { DesktopEngine.setShowKnowledgeBase(true) },
                        onToggleCaption = { DesktopEngine.setCaptionEnabled(it) }
                    )
                }
            }
        }
    }
}

# 灵眸 LingMou

Android 实时交互原型：**专注模式 / 烹饪模式 / 勿扰模式** 三种模式切换，把「摄像头看懂你在不在看屏幕」和「语音 + 头部动作控制播放」跑通。

当前阶段**刻意不做**数据库（Room / SQLDelight），也不做视频总结与菜谱生成。所有状态只存在内存里，进程结束即丢失。

- 包名：`com.example.lingmouapp`
- 语言 / UI：Kotlin + Jetpack Compose (Material 3)
- 编译目标：compileSdk 36 / minSdk 26 / targetSdk 36

---

## 一、需求 → 代码对照

| 需求 | 实现位置 |
| --- | --- |
| 三模式切换 + 高亮 + 状态区 + 播放占位区 | `ui/MainScreen.kt`、`ui/components/*` |
| CameraX + ML Kit 人脸检测 | `service/CameraFaceAnalyzer.kt` |
| 视线离开 >3 秒 → 暂停 | `service/GazeTracker.kt`（200ms 定时器驱动计时） |
| 视线回归 → 恢复播放 | `service/GazeTracker.kt` → `LingMouEngine.performMediaAction` |
| ≥2 张人脸 → 「有人靠近」通知 | `service/LingMouForegroundService.kt` + `NotificationHelper.kt` |
| 语音指令（暂停/继续/回退10秒） | `service/asr/*` + `service/nlp/SpeechCommandParser.kt` |
| 头部动作：点头标记 / 摇头回退5秒 | `service/HeadGestureDetector.kt`（基于 headEulerAngleX/Y） |
| 语音时间提取 → AlarmManager 精确闹钟 | `service/nlp/TimeExpressionParser.kt` + `service/ReminderScheduler.kt` |
| 勿扰模式彻底停掉摄像头与麦克风 | `LingMouForegroundService.shutdown()` |
| 跨应用媒体控制（MediaSession） | `service/MediaSessionController.kt` + `service/LingMouNotificationListener.kt` |
| 后台状态通知（前台服务） | `service/LingMouForegroundService.kt` |

---

## 二、目录结构

```
LingMouApp/
├── settings.gradle.kts              # 仓库源（阿里云镜像优先）+ 模块声明
├── build.gradle.kts                 # 顶层：只声明插件
├── gradle.properties                # JVM 参数、AndroidX、禁用 SDK 自动下载
├── local.properties                 # sdk.dir + 可选的硅基流动 API Key
├── gradle/libs.versions.toml        # 版本目录（唯一改版本号的地方）
├── gradlew / gradlew.bat / gradle/wrapper/
│
├── tools/
│   └── adb-install.ps1              # 装 APK 到真机 + 可选跟踪 logcat
│
└── app/
    ├── build.gradle.kts             # 依赖与签名；注入 BuildConfig 里的 API Key
    ├── proguard-rules.pro
    ├── src/main/
        ├── AndroidManifest.xml      # 权限、前台服务类型、通知监听服务、闹钟广播
        ├── res/                     # 图标、主题、备份规则
        └── java/com/example/lingmouapp/
            ├── LingMouApp.kt                    # Application：初始化引擎
            ├── MainActivity.kt                  # 权限申请 + Compose 宿主
            │
            ├── model/                           # ── 数据层（纯 Kotlin，无 Android 依赖）
            │   ├── AppMode.kt                   #   三模式定义（含 usesCamera/usesMicrophone）
            │   ├── AppUiState.kt                #   界面唯一状态
            │   ├── AttentionState.kt            #   注视状态 + FaceFrame
            │   ├── MediaAction.kt               #   暂停/播放/回退
            │   ├── SpeechCommand.kt             #   指令解析结果（sealed interface）
            │   ├── Reminder.kt                  #   一条定时提醒
            │   ├── PlayerState.kt               #   占位播放器状态
            │   ├── MediaSessionSnapshot.kt      #   媒体会话快照（会话数/目标/失败原因）
            │   └── CommandSelfTest.kt           #   本地指令自检结果
            │
            ├── viewmodel/
            │   └── MainViewModel.kt             # ── 只读 state，动作全部转交 service
            │
            ├── ui/
            │   ├── MainScreen.kt
            │   ├── theme/{Color,Theme}.kt
            │   └── components/
            │       ├── Common.kt                #   SectionCard / InfoRow
            │       ├── ModeSelector.kt
            │       ├── StatusPanel.kt           #   状态区 + 权限体检
            │       ├── CameraSection.kt
            │       ├── PlayerSection.kt
            │       ├── SpeechSection.kt
            │       ├── ReminderSection.kt
            │       └── LogSection.kt
            │
            └── service/                         # ── 真正碰硬件与系统的地方
                ├── LingMouEngine.kt             #   单例中枢：状态源 + 指令出口
                ├── LingMouForegroundService.kt  #   唯一持有摄像头/麦克风
                ├── CameraFaceAnalyzer.kt        #   CameraX ImageAnalysis + ML Kit
                ├── GazeTracker.kt               #   离开3秒 → 暂停 / 回归 → 播放
                ├── HeadGestureDetector.kt       #   点头 / 摇头
                ├── MediaSessionController.kt    #   跨应用媒体控制（会话筛选 + 诊断 + 变化监听）
                ├── SystemSettingsNavigator.kt   #   跳系统设置页（多级回退 + 回报落点）
                ├── LingMouNotificationListener.kt
                ├── NotificationHelper.kt
                ├── ReminderScheduler.kt         #   AlarmManager
                ├── ReminderReceiver.kt          #   到点广播
                ├── nlp/
                │   ├── ChineseNumberParser.kt   #   十五 → 15
                │   ├── TimeExpressionParser.kt  #   15分钟 / 半小时 / 一个半小时
                │   └── SpeechCommandParser.kt   #   关键词 → 指令
                └── asr/
                    ├── AsrEngine.kt             #   接口（换 Whisper.cpp 只改这里）
                    ├── SystemAsrEngine.kt       #   系统内置 SpeechRecognizer（默认）
                    ├── SiliconFlowAsrEngine.kt  #   硅基流动云端 ASR（Ktor）
                    └── WavRecorder.kt           #   AudioRecord + 静音切句 + WAV 封装

└── src/test/java/com/example/lingmouapp/service/nlp/
    └── SpeechCommandParserTest.kt     # 指令解析 JVM 单元测试（11 条，不需要真机）
```

---

## 三、编译

```powershell
# 环境（本机已装好）
$env:JAVA_HOME        = 'C:\Users\wxf20\.jdks\jdk-17.0.2'
$env:ANDROID_HOME     = 'C:\Users\wxf20\AppData\Local\Android\Sdk'

# 编译 debug 包
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 本机额外说明：受限沙箱会给已交付给界面的文件加排他句柄，导致 AGP 的
> `packageDebug` 无法清空输出目录。所以在这台机器上我用
> `.toolchain/redirect-build-dir.gradle`（通过 `--init-script`）把构建输出重定向到了
> `.toolchain/build-app/app/outputs/apk/debug/app-debug.apk`。
> **你自己用 Android Studio 或 `gradlew` 构建时走的是标准路径**，不受影响；
> `tools/adb-install.ps1` 会自动挑两个路径里最新的那份。

安装到已连接的真机：

```powershell
# 一条命令搞定：检查设备 → 安装 → 启动 → 跟日志
.\tools\adb-install.ps1 -Follow

# 也可以手动来
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

如果脚本提示 `No authorized device found`，按它打印的步骤开 USB 调试。
**必须用真机**：模拟器没有真实摄像头和麦克风，专注/烹饪模式没法验证。

跑一遍指令解析的 JVM 单元测试（不需要真机，也不需要 API Key）：

```powershell
.\gradlew.bat testDebugUnitTest
# 报告：app\build\reports\tests\testDebugUnitTest\index.html
```

> 如果 `gradlew` 下载 Gradle 很慢，`gradle/wrapper/gradle-wrapper.properties` 里的
> `distributionUrl` 已经指向腾讯镜像；也可以换回官方源
> `https://services.gradle.org/distributions/gradle-8.14.6-bin.zip`。

---

## 四、逐步验证

### 第 0 步：权限（必须先做）

打开 App 后会弹出运行时权限，全部允许。然后在「**权限体检**」卡片里确认 5 项：

| 项目 | 怎么给 |
| --- | --- |
| 摄像头 CAMERA | 弹窗允许 |
| 麦克风 RECORD_AUDIO | 弹窗允许 |
| 通知 POST_NOTIFICATIONS | 弹窗允许（Android 13+） |
| **通知使用权** | 点「去开启通知使用权」→ Android 11+ 会**直接落到本应用的开关页**；打开后返回 App 会 Toast「通知使用权已开启」。**这是跨应用媒体控制的前提** |
| **精确闹钟** | 点「去允许精确闹钟」→ 直接落到「闹钟和提醒」页，选允许 |

这两个开关系统不允许应用自己弹窗申请，只能跳设置页手动开。跳转做了多级回退
（`通知使用权详情页 → 通知使用权列表页 → 应用详情页`，`闹钟和提醒 → 应用详情页`），
每次点击都会 Toast 告诉你**实际落在哪一页**并写进应用内日志，所以 ROM 改过设置页也能顺着提示找到。

前 3 项没给，日志区会明确写出「缺少 XX 权限」。
后 2 项没给也不会崩：媒体指令会退化成只作用于占位播放器，定时提醒会退化成非精确闹钟。

### 第 1 步：模式切换 + 前台服务通知

1. 点「**专注模式**」→ 顶部状态区变成「专注模式运行中」，摄像头区出现实时画面。
2. 下拉通知栏 → 应该有一条常驻通知「专注模式运行中 · 摄像头：… · 人脸 N 张 · …」。
3. 按 Home 键退到后台 → 通知还在，且人脸数仍在刷新，说明检测没有停。
4. 点「**勿扰模式**」→ 通知消失，摄像头区显示「勿扰模式：摄像头已释放」，日志出现「已释放摄像头与麦克风」。

### 第 2 步：专注模式 —— 视线离开 3 秒暂停

1. 切到专注模式，正对前置摄像头，等 1～2 秒（状态区「注视状态」显示**正在注视屏幕**）。
2. 把头转向一侧 / 用手挡住摄像头 / 直接走开。
3. 状态区「已离开屏幕」开始跳秒；**超过 3 秒**后：
   - 日志出现 `视线离开屏幕已超过 3 秒 → 发送暂停`
   - 日志紧接着出现 `媒体指令「暂停」← 视线离开 3 秒（已下发系统媒体会话 / 无外部会话，仅作用于占位播放器）`
4. 转回头正对屏幕 → 日志出现 `视线回归屏幕 → 发送播放`。

> 想看到「已下发系统媒体会话」而不是「仅作用于占位播放器」，需要先在别的 App 里放一个视频（B 站 / 华为视频 / 浏览器），并且已经授予通知使用权。

**验证跨应用暂停**：先在 B 站播放一个视频 → 回到灵眸切专注模式 → 转头离开 3 秒 → B 站视频应当自动暂停 → 转回头 → 自动继续播放。

### 第 3 步：专注模式 —— 有人靠近

1. 保持专注模式，叫第二个人一起出现在前置摄像头画面里。
2. 状态区「画面中人脸」变成 2（或更多）。
3. 弹出通知「**有人靠近**」，内容为「画面里出现了 N 张人脸…」。
4. 日志出现 `检测到 N 张人脸 → 已发送「有人靠近」通知`。

> 冷却时间 30 秒，避免同一次靠近反复弹通知。

### 第 4 步：烹饪模式 —— 指令控制（**完全不需要麦克风，也不需要 API Key**）

「语音指令」卡片刻意分成两段：**A. 语音识别后端**（只影响「对着手机说话」）
和 **B. 本地指令测试**（直接调指令解析，与麦克风、API Key 全无关）。先验 B 段：

1. 切到烹饪模式。
2. 点「**运行本地指令自检（11 条用例）**」→ 应显示「自检结果：11/11 通过」，逐条列出。
   这一步不碰麦克风也不联网，全绿就说明指令链路本身是好的。
3. 逐个点快捷指令按钮：
   - `暂停` → 占位播放器变「已暂停」，日志 `识别到「暂停」→ 暂停播放`
   - `继续` → 恢复播放
   - `回退10秒` → 进度条回退 10 秒（**不会**被当成 10 秒定时，这是刻意做的优先级）
   - `15分钟后提醒我` → 「定时提醒」卡片出现一条 15 分钟后的提醒
   - `半小时` → 出现一条 30 分钟后的提醒
4. 也可以在输入框里自由输入「一小时后叫我」「回退5秒」「1小时30分钟后提醒」再点「送入指令解析」。

再验证**真实语音**（这一步才需要麦克风）：

5. 「当前识别后端」应显示 `系统内置 SpeechRecognizer`。
6. 对着手机说「暂停」「继续」「回退十秒」「十五分钟后提醒我」。
7. 日志会出现 `识别到「…」→ …`。

> **不想配 API Key 完全没问题。** 云端 ASR 只是「对着手机说话」这条路的可选项：
> 不填 Key 时开关自动禁用、默认走系统内置识别；B 段的手动模拟与自检按钮
> 无论有没有 Key、有没有麦克风都能照常触发控制行为。
> 真要切云端时，在 `local.properties` 填 `SILICONFLOW_API_KEY=sk-xxx` 重新编译即可。

### 第 5 步：烹饪模式 —— 点头 / 摇头

1. 保持烹饪模式，正对前置摄像头。
2. **点头**两次（幅度大一点，超过 ±12°）→ 日志 `头部动作：点头 → 在 mm:ss 打了一个进度标记`。
3. **摇头**两次（左右转，超过 ±15°）→ 日志 `头部动作：摇头 → 回退 5 秒`，占位播放器进度回退 5 秒。
4. 状态区「头部俯仰 / 偏航」会实时显示当前角度，可以用它判断动作幅度够不够。

> 冷却 1.8 秒，一次动作不会被重复计数。频繁误触发就把 `HeadGestureDetector` 的
> `nodThresholdDeg` / `shakeThresholdDeg` 调大。

### 第 6 步：定时提醒真的会响

1. 用「手动模拟一句话」输入 `1分钟后提醒我` 并送入。
2. 「定时提醒」卡片出现一条 1 分钟后的提醒，前台服务通知里出现「待触发提醒 1 个」。
3. **把 App 退到后台甚至锁屏**，等 1 分钟。
4. 弹出通知「**定时提醒** · 到时间了：1分钟」，日志出现 `定时提醒触发：1分钟`。
5. 如果没响：回第 0 步确认「精确闹钟」权限。没授权时会退化成非精确闹钟，可能延迟几分钟。

### 第 7 步：勿扰模式确实关掉了硬件

1. 从烹饪模式切到勿扰模式。
2. 系统状态栏里的**摄像头 / 麦克风使用指示点应该消失**。
3. 下拉通知栏，前台服务通知消失。
4. 状态区：摄像头「已释放」、麦克风「已释放」、人脸 0 张。
5. 再说「暂停」也不会有任何反应（服务已停）。

---

## 五、媒体会话排查（「没有可下发的目标」怎么办）

「下发暂停」后占位播放器一定会跟着动，但要让**外部 App**真的暂停，需要几个条件同时成立。
界面现在会把卡在哪一步直接写出来：

| 界面显示 | 含义 | 怎么办 |
| --- | --- | --- |
| `未授予通知使用权，跨应用控制不可用` | 第 0 步没做 | 点「去开启通知使用权」，打开开关 |
| `已授权，但当前没有活动媒体会话（先在 B 站/华为视频等播一个视频）` | 权限没问题，是确实没有会话 | 去 B 站/华为视频**真的播一个视频**，回来点「刷新媒体会话并写入日志」 |
| `已授权 · 发现 N 个会话 · 目标：xxx（播放中）` | 正常，指令会发给 xxx | 直接点「下发暂停」 |

点「刷新媒体会话并写入日志」，日志里会打印**所有**取到的会话及其状态：

```
媒体会话诊断：已授权 · 发现 2 个会话 · 目标：tv.danmaku.bili（播放中）
  会话清单：tv.danmaku.bili=播放中；com.huawei.hwmusic=已暂停
```

选目标的优先级：**正在播放 → 处于可控制状态（暂停/缓冲/快进快退/停止）→ 任意一个**。
最后一档是刻意保留的：不少国产 App 的 `playbackState` 上报不全（常常是 `STATE_NONE`），
但 `transportControls` 依然有效，不能因为状态不标准就放弃它。

授权成功后 App 会注册 `OnActiveSessionsChangedListener`，
你在别的 App 里开始/停止播放时，灵眸界面上的会话状态会自动变化，不必手动刷新。

有两点由外部 App 决定、我们控制不了：

- 有些 App 不注册 `MediaSession`，或注册了但不响应外部 `pause()`；
- 部分 ROM 把自家视频 App 的会话放在受保护通道里。

遇到这类情况，占位播放器仍会正常联动，日志会写「无外部会话，仅作用于占位播放器」。

> 小提示：界面初始状态显示的是「尚未下发过指令」，而不是「没有可下发的目标」——
> 之前那版文案会让人误以为第一次点就已经失败了。

---

## 六、已知取舍与后续可替换点

| 点 | 现在的做法 | 想升级时改哪里 |
| --- | --- | --- |
| 视线判定 | ML Kit 不提供真实视线向量，用「头部偏航/俯仰 ≤20° 且双眼睁开概率 ≥0.35」近似 | `CameraFaceAnalyzer.toFrame()` 一个函数；换 MediaPipe Face Mesh 虹膜即可，上层不用动 |
| 头部动作 | 姿态角阈值 + 时间窗内方向翻转计数 | `HeadGestureDetector.kt` |
| 语音识别 | 系统内置 SpeechRecognizer（默认）/ 硅基流动云端 ASR（可选） | 新增一个 `AsrEngine` 实现即可 |
| 时间理解 | 正则 + 中文数字，不做完整 NLU | `service/nlp/*` |
| 定时提醒 | 只存内存，进程被杀就丢 | 接 Room 持久化 + `BOOT_COMPLETED` 重建闹钟 |
| 架构 | 单例 `LingMouEngine` 而非 Hilt/Koin | 需要时把它拆成 Repository + DI |
| 「点头标记进度」 | 只记录进度点（无持久化） | 接数据库后即可保存章节标记 |
| 媒体会话选目标 | 正在播放 → 可控制状态 → 任意一个 | `MediaSessionController.primaryController()`；要更聪明可以按包名白名单排序 |
| 权限跳转 | 每类设置页做多级回退，并把落点回报给界面 | `service/SystemSettingsNavigator.kt`；新增设置项在这里加入口 |
| 指令自检 | 11 条固定用例，JVM 单元测试同源 | `SpeechCommandParser.selfTest()` 与 `app/src/test/.../SpeechCommandParserTest.kt` |

---

## 七、这个工程跑起来的两个特殊之处

1. **相机归前台服务所有，不归 Activity。**
   `LingMouForegroundService` 是 `LifecycleService`，用服务自己的生命周期绑定 CameraX，
   界面只是通过 `LingMouEngine.preview` 拿到 `Preview` 用例、把 `PreviewView` 的
   `SurfaceProvider` 挂上去。这样退到后台检测不会断，也不会出现 Activity 与 Service 抢相机。

2. **构建环境相关的两处让步**（正常 Android Studio 里不影响）：
   - `.toolchain/keystore/debug.keystore`：当 `~/.android` 不可写时用工程内签名，保证 `assembleDebug` 能出包。
   - `android.builder.sdkDownload=false`：SDK 组件已预装，禁止 AGP 再去写 SDK 目录。

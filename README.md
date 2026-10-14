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
| 语音指令（暂停/继续/回退10秒） | `service/nlp/LocalCommandRouter.kt` → `service/nlp/SpeechCommandParser.kt`（`service/asr/*` 只负责把语音变成文本） |
| 头部动作：点头标记 / 摇头回退5秒 | `service/gesture/HeadGestureClassifier.kt`（纯 Kotlin 判定）+ `service/HeadGestureDetector.kt`（接 ML Kit 姿态角） |
| 语音时间提取 → AlarmManager 精确闹钟 | `service/nlp/TimeExpressionParser.kt` + `service/ReminderScheduler.kt` |
| 勿扰模式彻底停掉摄像头与麦克风 | `LingMouForegroundService.shutdown()` |
| 跨应用媒体控制（MediaSession） | `service/MediaSessionController.kt` + `service/MediaNotificationListener.kt` |
| AI 字幕 | `service/LingMouEngine.kt`（`setCaptionEnabled` / `stopCaption`）+ `ui/components/CaptionSection.kt` |
| 本地数据库（五张表） | `shared/src/main/sqldelight/...`（SQLDelight）+ `shared/.../KnowledgeRepository.kt` |
| 数据库的 Android 接入 | `service/DatabaseService.kt`（换驱动即可给 Windows 复用） |
| 云端内容分析（总结/菜谱） | `service/AIService.kt` 的 `summarizeVideo` / `extractRecipe`（JSON 约束输出） |
| 查看笔记 / 菜谱 | `ui/KnowledgeScreen.kt` + `ui/components/KnowledgeSection.kt` |
| 后台状态通知（前台服务） | `service/LingMouForegroundService.kt` |

---

## 二、目录结构

```
LingMouApp/
├── settings.gradle.kts              # 仓库源（官方源优先，阿里云兜底）+ 模块声明
├── build.gradle.kts                 # 顶层：只声明插件（含 kotlin.jvm / sqldelight）
├── gradle.properties                # JVM 参数、AndroidX、HTTP 超时上限
├── local.properties                 # sdk.dir + 可选的 ASR / LLM 配置
├── gradle/libs.versions.toml        # 版本目录（唯一改版本号的地方）
├── gradlew / gradlew.bat / gradle/wrapper/
│
├── tools/
│   └── adb-install.ps1              # 装 APK 到真机 + 可选跟踪 logcat
│
├── shared/                          # ★ 平台无关的数据/领域层（纯 Kotlin JVM）
│   ├── build.gradle.kts             #   kotlin.jvm + sqldelight 插件
│   └── src/
│       ├── main/sqldelight/com/example/lingmouapp/db/
│       │   ├── StudyNote.sq         #   学习笔记
│       │   ├── FocusSession.sq      #   专注会话
│       │   ├── DistractionRecord.sq #   分心记录
│       │   ├── Recipe.sq            #   菜谱
│       │   ├── RecipeStep.sq        #   菜谱步骤
│       │   └── Meta.sq              #   last_insert_rowid（连接级，五表共用）
│       ├── main/kotlin/com/example/lingmouapp/
│       │   ├── db/DatabaseDriverFactory.kt   # Android / JVM 两种驱动的抽象
│       │   ├── db/KnowledgeRepository.kt     # ★ 全部 CRUD，无 Android 依赖
│       │   └── shared/ContentAnalysis.kt     # VideoSummary / RecipeDraft
│       └── test/kotlin/.../KnowledgeRepositoryTest.kt  # 8 条真 SQLite 测试
│
└── app/    ├── build.gradle.kts             # 依赖与签名；注入 BuildConfig 里的 API Key
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
            │       ├── CaptionSection.kt        #   AI 字幕（紧跟在视频占位区下方）
            │       ├── SpeechSection.kt
            │       ├── ReminderSection.kt
            │       └── LogSection.kt
            │
            └── service/                         # ── 真正碰硬件与系统的地方
                ├── LingMouEngine.kt             #   单例中枢：状态源 + 指令出口
                ├── AIService.kt                 #   硅基流动 ASR 的 Ktor 封装（异常全收敛成 Result）
                ├── LingMouForegroundService.kt  #   唯一持有摄像头
                ├── CameraFaceAnalyzer.kt        #   CameraX ImageAnalysis + ML Kit
                ├── GazeTracker.kt               #   离开3秒 → 暂停 / 回归 → 播放
                ├── HeadGestureDetector.kt       #   点头 / 摇头（Android 侧：时间源 + 回调）
                ├── gesture/
                │   ├── HeadGesture.kt           #   结果枚举，"nod"/"shake"/"none"
                │   └── HeadGestureClassifier.kt #   ★ 纯 Kotlin 判定规则，11 条 JVM 单测
                ├── MediaSessionController.kt    #   跨应用媒体控制（会话筛选 + 诊断 + 变化监听）
                ├── SystemSettingsNavigator.kt   #   跳系统设置页（多级回退 + 回报落点）
                ├── MediaNotificationListener.kt
                │                                #   通知监听服务（通知使用权 = 读媒体会话的特权）
                │                                #   onListenerConnected 会即时回写授权状态
                ├── NotificationHelper.kt
                ├── ReminderScheduler.kt         #   AlarmManager
                ├── ReminderReceiver.kt          #   到点广播
                ├── nlp/
                │   ├── ChineseNumberParser.kt   #   十五 → 15
                │   ├── TimeExpressionParser.kt  #   15分钟 / 半小时 / 一个半小时
                │   ├── SpeechCommandParser.kt   #   关键词 → 命令对象
                │   └── LocalCommandRouter.kt    #   命令对象 → 最终控制动作
                │                                #   ★ 纯 Kotlin，无 Android/网络/Key 依赖
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
| **通知使用权** | 点「去开启通知使用权」→ Android 11+ 用 `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` **直接落到「灵眸」的开关**；打开后返回 App 会自动刷新。**这是跨应用媒体控制的前提** |
| **精确闹钟** | 点「去允许精确闹钟」→ 落到「闹钟和提醒」页选允许 |

这两个开关系统不允许应用自己弹窗申请，只能跳设置页手动开。跳转做了多级回退
（`通知使用权详情页 → 通知使用权列表页 → 应用详情页`，`闹钟和提醒 → 应用详情页`），
每次点击都会 Toast 告诉你**实际落在哪一页**并写进应用内日志，所以 ROM 改过设置页也能顺着提示找到。

**关于「精确闹钟显示未授予」**：这不是 Bug。Android 14 起，只要 `targetSdk >= 33`，
`SCHEDULE_EXACT_ALARM` **默认就是不给的**，必须由用户在设置页手动允许。本工程 targetSdk = 36，
所以初次进来必然是「未授予」。没授权时会自动退化为 `setAndAllowWhileIdle`，功能不失效但可能延迟。

**关于按钮行为**：两个按钮都会先判断当前状态 —— **已经授权时不会再跳转**，
直接 Toast「已授予，无需操作」，避免你在设置页里白找一圈；未授权时才跳转。

> ⚠️ 系统**不允许**应用自己弹窗申请这两个权限，只能跳设置页，必须手动打开。

前 3 项运行时权限没给，日志区会明确写出「缺少 XX 权限」。
后 2 项没给也不会崩：媒体指令会退化成只作用于占位播放器，定时提醒会退化成非精确闹钟。

#### ColorOS（OPPO A97 5G）路径参考

| 要开的开关 | 路径 |
| --- | --- |
| 通知使用权 | 点 App 内按钮直达；手动走：设置 → 通知与状态栏 → 通知使用权 → 灵眸 |
| 精确闹钟 | 点 App 内按钮直达；手动走：设置 → 应用 → 应用管理 → 灵眸 → 闹钟和提醒 |
| **自启动 / 后台运行**（关键） | 设置 → 应用 → 应用管理 → 灵眸 → **耗电管理** → 允许后台运行；再到「自启动」里允许 |
| 锁屏显示通知 | 设置 → 通知与状态栏 → 灵眸 → 允许锁屏通知 |

ColorOS 对后台应用管得很严。**如果定时提醒到点没响，九成是「耗电管理」把后台冻结了**，
而不是权限没给。App 内「应用详情页（ColorOS：自启动 / 耗电管理）」按钮可以直接跳到那一页。

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

「语音指令」卡片分成两段：**A. 语音识别后端**（只影响「对着手机说话」）
和 **B. 本地指令测试**（与麦克风、API Key 全无关）。

> 这条链路为什么不可能依赖 API Key：
> `MainViewModel.simulateSpeech` → `LingMouEngine.runManualCommand`
> → `LocalCommandRouter.route` → `SpeechCommandParser.parse`。
> `LocalCommandRouter` 是纯 Kotlin 文件，**只 import 三个 model 类型**，
> 不 import 任何 Android / Ktor / BuildConfig —— 想让它联网必须先改 import，一眼可见。
> 语音识别结果走的是同一个 `LocalCommandRouter`，所以手动与语音行为完全一致。

先验 B 段：

1. 切到烹饪模式。
2. 点「**运行本地指令自检（11 条用例 → 校验最终动作）**」→ 应显示「自检结果：11/11 通过」。
   它校验的是**最终会调用哪个控制动作**（暂停播放 / 继续播放 / 回退 N 秒 / 设置定时提醒），
   而不只是中间的命令对象。不碰麦克风、不联网。
3. 逐个点快捷指令按钮，并到上方「**状态显示区 → 本地指令闭环**」确认反馈：
   - `暂停` → 「最近识别：暂停」「解析结果：暂停播放」；占位播放器变「已暂停」
   - `继续` → 恢复播放
   - `回退10秒` → 进度条回退 10 秒（**不会**被当成 10 秒定时，这是刻意做的优先级）
   - `15分钟后提醒我` → 「待触发提醒 1 个」，定时提醒卡片出现一条 15 分钟后的提醒
   - `半小时` → 出现一条 30 分钟后的提醒
4. 也可以在输入框里自由输入「一小时后叫我」「回退5秒」「1小时30分钟后提醒」再点「送入指令解析」。

> 即使识别后端显示「未启动」、或设备根本没有语音服务，第 2～4 步也**必须照常工作**。

再验证**真实语音**（这一步才需要麦克风）：

5. 「当前识别后端」应显示 `系统内置 SpeechRecognizer`。
6. 对着手机说「暂停」「继续」「回退十秒」「十五分钟后提醒我」。
7. 日志会出现 `[语音识别] 收到「…」→ …`。

> **不想配 API Key 完全没问题。** 云端 ASR 只是「对着手机说话」这条路的可选项：
> 不填 Key 时开关自动禁用、默认走系统内置识别；B 段的手动模拟与自检按钮
> 无论有没有 Key、有没有麦克风都能照常触发控制行为。
> 真要切云端时，在 `local.properties` 填 `SILICONFLOW_API_KEY=sk-xxx` 重新编译即可。

### 第 5 步：烹饪模式 —— 头部动作控制（点头 / 摇头）

> ⚠️ 规则在最近一次改动中变了：**现在是单次点头 / 单次摇头即可识别**。
> 旧版本要求「2 次方向翻转」= 下-上-下 三个极值，单次动作永远不会触发，那是个真 bug。

1. 切到**烹饪模式**（会自动开启摄像头）。
2. 状态显示区应出现「**头部控制：已启用**」。
   - 切到专注模式或勿扰模式后，这一行会变成「未启用」。
3. 正对前置摄像头，**点头一次**（幅度超过 ±12°，大约 400ms 一个来回）→
   状态区「最近头部动作」变成 `点头 → 已在 mm:ss 打标记`，日志出现
   `头部动作：点头（nod）→ 在 mm:ss 打了一个进度标记`。
4. **摇头一次**（左右各转超过 ±15°）→ 状态区显示 `摇头 → 回退 5 秒`，
   占位播放器进度回退 5 秒，日志 `媒体指令「回退 5 秒」← 头部动作：摇头`。
5. 状态区的「**俯仰 / 偏航 / 翻滚**」实时显示当前角度，用它判断幅度够不够。

#### 判定规则与防误触

| 参数 | 值 | 作用 |
| --- | --- | --- |
| 点头阈值 | ±12°（pitch / headEulerAngleX） | 轻微晃动不算极值 |
| 摇头阈值 | ±15°（yaw / headEulerAngleY） | 同上 |
| 最小极值间隔 | 250ms | 滤掉单帧抖动造成的假翻转 |
| 判定窗口 | 1400ms | 两个反向极值必须落在窗口内 |
| 冷却 | 1800ms | 两种动作**共享**，避免斜向摆头一次判出两个动作 |

这套规则在 [HeadGestureClassifier.kt](<C:\Users\wxf20\Desktop\LingMouApp\app\src\main\java\com\example\lingmouapp\service\gesture\HeadGestureClassifier.kt>)
里，是**纯 Kotlin（零 import）**，由 11 条 JVM 单测覆盖「抖动 / 保持不动 / 窗口过期 / 冷却 / 阈值边界」。
想调灵敏度就改构造参数，不用碰 Android 代码。

> 如果频繁误触发：把 `nodThresholdDeg` / `shakeThresholdDeg` 调大，
> 或把 `minExtremeGapMs` 也调大（例如 350ms）。

### 第 6 步：定时提醒真的会响

**方式一（等真实时间，最稳）**

1. 先在「权限体检」里把**精确闹钟**允许了。
2. 用「手动模拟一句话」输入 `1分钟后提醒我` 并送入。
   > 注意：**授权之前设的提醒不会自动升级成精确闹钟**，一定要在授权之后重新设一条。
3. 「定时提醒」卡片出现一条 1 分钟后的提醒，前台服务通知里出现「待触发提醒 1 个」。
4. **把 App 退到后台甚至锁屏**，等 1 分钟。
5. 弹出通知「**定时提醒** · 到时间了：1分钟」，日志出现 `定时提醒触发：1分钟`。

**方式二（改系统时间，验得快）**

1. 设一条 `15分钟后提醒我`。
2. 进 设置 → 系统设置 → 日期与时间 → **关掉「自动设置」**，把时间往后拨 16 分钟。
3. 因为用的是 `AlarmManager.RTC_WAKEUP`（跟随墙上时钟），拨快时间会立刻触发。
4. 弹通知后，**记得把时间调回自动**。

如果两种方式都没响，按这个顺序排查：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 「权限体检」里精确闹钟仍是「未授予」 | 没授权或被系统回收 | 重新点按钮授权，再重设提醒 |
| 权限已授予，锁屏后不响 | ColorOS 冻结了后台 | 应用详情 → 耗电管理 → 允许后台运行 + 打开自启动 |
| 通知栏完全没有 | POST_NOTIFICATIONS 被关 | 设置 → 通知与状态栏 → 灵眸 → 允许通知 |
| 应用内日志出现了 `定时提醒触发`，但没通知 | 通知被渠道设置屏蔽 | 检查「事件提醒」渠道是否被静音 |

### 第 7 步：勿扰模式确实关掉了硬件

1. 从烹饪模式切到勿扰模式。
2. 系统状态栏里的**摄像头 / 麦克风使用指示点应该消失**。
3. 下拉通知栏，前台服务通知消失。
4. 状态区：摄像头「已释放」、麦克风「已释放」、人脸 0 张。
5. 再说「暂停」也不会有任何反应（服务已停）。

---

### 第 8 步：云端语音识别（可选，需要 API Key）

**先说结论：不配 Key 也能完整跑。** 打开开关会立刻自动降级为系统内置识别，
并把原因写在开关下方。

1. 在 `local.properties` 填 `SILICONFLOW_API_KEY=sk-xxxx`；**模型名留空即可**
   （构建时回退到 `FunAudioLLM/SenseVoiceSmall`，并在界面上标注「默认值」）。
   想换模型就填 `SILICONFLOW_ASR_MODEL=TeleAI/TeleSpeechASR` 之类。
2. 重新编译安装：`.\gradlew.bat assembleDebug`。
3. 烹饪模式 → 语音指令卡片 → 打开「**云端识别（硅基流动）**」开关。
4. 「当前识别后端」应显示 `硅基流动云端 ASR（FunAudioLLM/SenseVoiceSmall）`。
5. 对着手机说「**暂停**」，观察三处反馈：
   - 状态区「最近识别」出现识别文本（可能有个别错字）
   - 「解析结果」出现 `暂停播放`
   - 日志出现 `[语音识别] 收到「暂停」→ 暂停播放`
6. 再说「继续」「回退十秒」「十五分钟后提醒我」验证其余分支。

#### 降级验证（故意制造失败，确认不会崩）

| 场景 | 操作 | 预期 |
| --- | --- | --- |
| 没有 Key | `SILICONFLOW_API_KEY=` 留空，打开开关 | **立即**降级；开关下方红字写明「未配置 SILICONFLOW_API_KEY → 已自动降级为系统内置识别」；不崩 |
| Key 无效 | 填一个假 `sk-xxx` | 单次失败只重试；连续 3 次失败后自动切系统内置识别并写明原因 |
| 断网 | 开飞行模式后打开开关 | 同上，连续失败后降级 |
| 缺麦克风权限 | 拒绝 RECORD_AUDIO | 状态区显示「缺少 RECORD_AUDIO 权限」，**B 段本地指令测试照常可用** |

> 注意：云端识别在后台持续运行依赖前台服务（麦克风类型），
> 所以要边做别的事边说指令，请保持**烹饪模式**；在勿扰模式下开启只在前台有效。

---

### 第 9 步：AI 字幕

字幕**复用语音识别的原文**，不额外开 AudioRecord（否则会和识别器抢麦克风）。
所以它只有在语音识别运行时才有内容 —— 这是刻意的设计，也让「切模式必清空」成为必然。

1. 切到**烹饪模式**。
2. 视频占位区**正下方**是「AI 字幕」卡片，此时显示灰字「**字幕未开启**」。
3. 点「**开启字幕**」：
   - 按钮变成「关闭字幕」
   - 显示区变成「字幕已开启 · 正在聆听…」
   - 如果识别还没在跑，会自动把它拉起来（日志出现 `[字幕] 已开启` + `[AI 字幕] 语音识别已启动：…`）
4. **对着手机说一句正常的话**（比如「今天天气不错」或跟着视频念台词），停顿一下：
   - 显示区出现识别原文
   - 注意：**即使这句话不是控制指令，字幕照样显示** —— 字幕在进入指令解析之前就取原文了
5. 说「暂停」时，字幕显示「暂停」，同时照常触发暂停。

#### 清空与关闭的验证

| 操作 | 预期 |
| --- | --- |
| 点「关闭字幕」 | 显示区立刻变回「字幕未开启」，文本清空 |
| 从烹饪模式切到**专注模式** | 字幕自动关闭并清空；日志 `[字幕] 已关闭（切换模式 烹饪模式 → 专注模式）` |
| 从烹饪模式切到**勿扰模式** | 同上，且麦克风释放 |
| 关闭「云端识别」开关（烹饪模式下） | 字幕**不受影响**（只是换回系统内置识别），文本保留 |
| 手动停止语音识别 | 字幕随之关闭，显示区提示「语音识别已停止，字幕随之关闭」 |

> 字幕会不会显示错别字？会。识别质量取决于后端：系统内置识别看设备语音服务，
> 云端识别看硅基流动的模型。字幕是「原文转写」，不做任何纠错。

---

### 第 10 步：本地数据库 + 云端内容分析（笔记 / 菜谱）

**先说清楚文字稿从哪来**：ASR 是「逐句识别、立刻当指令消费掉」的，所以我在引擎里加了
**会话级文字稿累积**（`LingMouEngine.transcriptBuffer`）。会话边界 = 模式切换：
切模式时结算上一段（专注模式会写入 `FocusSession` + `DistractionRecord`），并开启新一段。
**手动模拟输入不计入**，否则调试文字会污染总结内容。

1. 切到**专注模式或烹饪模式**，让语音识别（或至少 AI 字幕）跑一会儿，说几句话。
2. 回主页面，「**云端内容分析 · 笔记 / 菜谱**」卡片里会显示「本会话文字稿：N 句」。
3. 点「**生成学习笔记**」或「**生成菜谱**」：
   - 未配置 `SILICONFLOW_API_KEY` → 卡片上直接写「未配置 SILICONFLOW_API_KEY，无法生成…」，
     **不发起请求、不崩溃**
   - 文字稿为空 → 提示「先开启语音…」
   - 正常 → 显示「正在生成…」，完成后显示「已保存笔记「xxx」」
4. 点「**查看笔记 / 菜谱**」进入知识库页面，能看到历史记录、每条的时间和模式标签，可以单条删除。

#### 数据库相关验证

| 项目 | 说明 |
| --- | --- |
| 五张表 | `StudyNote` / `FocusSession` / `DistractionRecord` / `Recipe` / `RecipeStep` |
| 存放位置 | App 私有目录 `lingmou.db`（Android 用系统 SQLite，不额外带 native 库） |
| 跨平台 | 表结构与 CRUD 在 `:shared` 纯 Kotlin 模块里；换 `JdbcSqliteDriver` 即可给 Windows 用 |
| 单测 | `shared` 模块有 8 条**跑真 SQLite（内存库）**的仓库测试，不需要真机 |

> 注意：生成走的是硅基流动的**对话**接口（`/v1/chat/completions`），
> 和语音识别（`/v1/audio/transcriptions`）是两个 endpoint、两个模型，
> 分别由 `SILICONFLOW_ASR_MODEL` 与 `SILICONFLOW_LLM_MODEL` 配置，都可以留空取默认值。

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
| 头部动作 | 姿态角阈值 + 交替极值 + 最小间隔/窗口/共享冷却（纯 Kotlin，可单测） | `service/gesture/HeadGestureClassifier.kt` |
| 语音识别 | 系统内置 SpeechRecognizer（默认）/ 硅基流动云端 ASR（可选，HTTP 封装在 `service/AIService.kt`） | 新增一个 `AsrEngine` 实现即可 |
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

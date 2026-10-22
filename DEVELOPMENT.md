# 灵眸 LingMou V2.0 开发文档

> 版本：V2.0（含 Vosk 本地离线语音识别）
> 平台：Android（主）+ Windows 桌面端（演示/验证）
> 更新日期：2026-10-22
>
> 本文档与当前代码仓库逐条对应。凡文中提到的阈值、表结构、文件路径、版本号，
> 均以仓库实际代码为准，未包含任何计划中但未实现的功能。

---

## 1. 项目概述

### 1.1 项目背景与定位

灵眸 LingMou 是一款**基于 KMP（Kotlin Multiplatform）与端云协同的智能视频交互助手**。
它把手机前置摄像头、麦克风变成「免动手」的媒体控制输入设备：

- **视线控制**：看视频时脸移开屏幕 → 自动暂停；脸回来 → 自动继续；
- **头部动作控制**：点头打进度标记、摇头回退 5 秒（面向满手油污的烹饪场景）；
- **语音控制**：说「暂停 / 继续 / 回退 / 15分钟后提醒我」即可操控播放并设定时；
- **跨应用控制**：通过系统 MediaSession 机制，把指令下发给 B 站、华为视频等
  **正在播放的第三方 App**；
- **本地知识沉淀**：观看/烹饪过程中自动记录学习笔记与菜谱，本地 SQLite 存储。

核心设计原则：**端侧优先**。人脸检测（ML Kit，bundled 版本）、语音识别（Vosk 本地模型）
全部在手机本地完成，不依赖云端、不依赖 Google Play 服务；云端（硅基流动 ASR/LLM）
只作为**可选的高精度模式**存在，缺 Key、断网都不影响核心功能。

### 1.2 核心目标用户与使用场景

| 用户 | 场景 |
| --- | --- |
| 看网课/论文视频的学生 | 专注模式：视线离开自动暂停、回来自动继续，配合笔记沉淀 |
| 做饭时看菜谱视频的烹饪者 | 烹饪模式：手上沾油也能用头部动作与语音控制，自动生成菜谱 |
| 想要「免打扰」观影的用户 | 勿扰模式：一键静默，释放摄像头与麦克风 |
| 关注隐私的用户 | 语音识别全程本地（Vosk），不上传音频 |

---

## 2. 技术架构与选型

### 2.1 整体架构

```
┌────────────────────────────────────────────────────────────┐
│ UI 层（shared/src/commonMain/kotlin/.../ui，Android 与桌面共用）│
│   AppShell（底部导航：模式 / 用户）                            │
│     ├─ ModeTab：标题 / 胶囊模式切换 / 状态卡 / 相机 / 播放区     │
│     └─ UserTab：用户信息 / 权限管理 / 笔记·食谱列表→详情         │
│   平台差异仅两处注入：previewSlot（Android 传 CameraX 画面）      │
│                      capabilities（平台能力清单，驱动“暂不支持”标注）│
├────────────────────────────────────────────────────────────┤
│ ViewModel 层（app/.../viewmodel/MainViewModel.kt）          │
│   纯转发层：不持有设备资源，全部委托给 LingMouEngine            │
├────────────────────────────────────────────────────────────┤
│ Service 层（app/.../service/）                               │
│   LingMouEngine           状态中枢（唯一 StateFlow 源）        │
│   LingMouForegroundService 摄像头/麦克风所有者（前台服务）      │
│   CameraFaceAnalyzer       CameraX + ML Kit 人脸分析          │
│   GazeTracker              视线离开计时状态机                 │
│   HeadGestureDetector      头部动作封装（分类器在 shared）      │
│   asr/ VoskSpeechService  本地离线识别（默认后端）             │
│   asr/ SystemAsrEngine     系统内置识别（兜底）               │
│   asr/ SiliconFlowAsrEngine 云端 ASR（可选高精度，失败静默降级）│
│   MediaSessionController   跨应用媒体控制（每次指令全新查询）   │
│   ReminderScheduler/Receiver 精确/非精确定时提醒               │
│   DatabaseService          数据库 Android 侧接线与映射         │
├────────────────────────────────────────────────────────────┤
│ 数据层（shared/src/commonMain/.../db）                       │
│   KnowledgeRepository      平台无关 CRUD（Android/桌面共用）    │
│   SQLDelight 生成代码       LingMouDatabase（5 张表）          │
│   驱动：Android=AndroidSqliteDriver；桌面=JdbcSqliteDriver     │
└────────────────────────────────────────────────────────────┘
```

### 2.2 跨平台方案：KMP + Compose Multiplatform 职责划分

- `:shared` 是 **KMP 模块**，两个 target：`androidTarget()`（给 :app）与 `jvm()`（给 :desktop）。
  职责：
  - `commonMain/model/`：全部业务模型（`AppUiState`、`AppMode`、`PlayerState`、
    `PlatformCapabilities`、`NoteItem/RecipeItem` 等，10 个文件，零 Android 依赖）；
  - `commonMain/db/`：`KnowledgeRepository`（平台无关 CRUD）+ `DatabaseDriverFactory` 抽象；
  - `commonMain/service/nlp/`：`LocalCommandRouter` / `SpeechCommandParser` /
    `TimeExpressionParser` / `ChineseNumberParser`（纯 Kotlin，可 JVM 单测）；
  - `commonMain/service/gesture/`：`HeadGestureClassifier` / `HeadGesture`（纯 Kotlin）；
  - `commonMain/ui/`：**全部 Compose 组件**（Android 与桌面逐字共用同一份源码）；
  - `jvmMain/`：`JvmFileDatabaseFactory` / `JvmDatabaseDriverFactory`（sqlite-jdbc，
    用 `compileOnly` 隔离，不进 Android APK）；
  - `jvmTest/`：全部 39 条 JVM 单测（跑真 SQLite 内存库）。
- `:app`：Android 壳。CameraX/ML Kit/MediaSession/AlarmManager/SpeechRecognizer/Vosk
  全部只在这里；`ui/AndroidCameraPreview.kt` 是唯一认识 CameraX 类型的 Compose 代码。
- `:desktop`：Compose for Desktop 壳（`main.kt` + `DesktopEngine`）。复用 shared 的
  UI/模型/仓库/指令解析；用占位播放器 + `ScheduledExecutorService` 演示闭环，
  不支持能力由 `PlatformCapabilities.WINDOWS.unsupported` 在 UI 上逐条标注。

**平台差异收口点只有三处**：数据库驱动（`DatabaseDriverFactory`）、相机画面
（`previewSlot` 槽位）、能力清单（`PlatformCapabilities`）。

### 2.3 核心技术栈

| 领域 | 选型 | 版本 | 说明 |
| --- | --- | --- | --- |
| 语言/构建 | Kotlin / Gradle / AGP | 2.2.21 / 8.14.6 / 8.13.2 | 版本统一由 `gradle/libs.versions.toml` 管理 |
| UI | Jetpack Compose + Compose Multiplatform | BOM 2025.09.01 / CMP 1.9.3 | 共享 UI 用 CMP（Android 侧重定向 androidx 构件） |
| 视觉 | CameraX + ML Kit Face Detection | 1.5.3 / 16.1.7 | bundled 版，模型随 APK 下发，无 GMS 依赖 |
| 语音（默认） | Vosk 本地离线识别 | 0.3.47 | 中文小模型约 42MB，打进 assets，首次启动解包到内部存储 |
| 语音（兜底） | Android SpeechRecognizer | 系统内置 | Vosk 不可用时自动切换 |
| 语音（可选） | 硅基流动云端 ASR（Ktor 3.3.0） | SenseVoiceSmall | 失败一次即静默降级本地 |
| 媒体控制 | MediaSessionManager + MediaController | Android API | 需通知使用权；每次指令重新查询会话 |
| 定时 | AlarmManager（setExactAndAllowWhileIdle） | — | 无精确闹钟权限时降级 setAndAllowWhileIdle |
| 存储 | SQLDelight + SQLite | 2.2.1 | 5 张表；Android 用系统 SQLite，桌面用 sqlite-jdbc |
| 协程 | kotlinx.coroutines | 1.10.2 | 引擎/服务/识别各自独立 scope |

---

## 3. 核心功能模块实现

### 3.1 专注模式：视线控制

**文件**：`service/CameraFaceAnalyzer.kt`、`service/GazeTracker.kt`、
`service/LingMouForegroundService.kt`（`handleFaceFrame`/`startTicker`）、
`service/LingMouEngine.kt`（`performMediaAction`）。

- **人脸检测**：CameraX `ImageAnalysis`（`STRATEGY_KEEP_ONLY_LATEST`）+ ML Kit
  `PERFORMANCE_MODE_FAST`、`minFaceSize=0.15`、`CLASSIFICATION_MODE_ALL`、`enableTracking()`。
  每个成功回调生成 `FaceFrame(faceCount, attentive, pitch/yaw/roll, eyeOpenProbability)`。
- **注视判定**（`toFrame`）：取最大人脸，`attentive = |yaw|≤20° ∧ |pitch|≤20° ∧ 双眼睁开概率≥0.35`
  （睁眼概率缺失时视为睁眼）。检测到 0 张人脸 → `attentive=false`。
- **3 秒防抖状态机**（`GazeTracker`）：
  - `onFrame(hasFace, attentiveNow)` 每帧调用；**只要见过一次人脸即 arm**
    （v1 曾要求"注视过一次"，在真机上因角度阈值过严永远无法 arm，已放宽）；
  - 服务内 200ms 的 ticker 调 `tick()`：持续不注视满 `DEFAULT_AWAY_THRESHOLD_MS=3000` → 触发暂停；
  - 恢复注视 → 触发播放；
  - **动作冷却 `DEFAULT_ACTION_COOLDOWN_MS=2000`**：暂停/恢复后 2 秒内不触发反向动作
    （防止人脸在屏幕边缘进出导致闪烁，答辩演示保障）；
  - 状态先置位、回调包 try/catch——回调异常也不会卡死状态机。
- 人脸帧 → UI 状态（`LingMouEngine.onFaceFrame`）节流到 **100ms/次**（此前每帧 20-30 次
  全界面重组拖慢主线程）；视线判定在服务侧每帧进行，不受节流影响。
- 帧率/耗时可观测：`CameraFaceAnalyzer` 每 3 秒打一条 `LingMouFps` 日志
  （分析帧率 + ML Kit 单帧最长耗时）。

### 3.2 烹饪模式：头部动作 + 语音 + 定时

**头部动作**（`shared/.../service/gesture/HeadGestureClassifier.kt`、
`service/HeadGestureDetector.kt`）：

- 判定规则：一次动作 = 头部先后到达**两个方向相反的极值**（REQUIRED_EXTREMES=2）。
  点头看 pitch（ML Kit `headEulerAngleX`），阈值 ±12°；摇头看 yaw（`headEulerAngleY`），阈值 ±15°。
- 三重防误触：越阈才算极值；相邻极值间隔 ≥250ms；两个极值须落在 **1400ms 窗口**内；
  两类动作共享 **1000ms 冷却**（v1 为 1800ms，连续指令明显迟钝，已调低）。
- 输出 `"nod"/"shake"/"none"`；点头 → `onNodDetected()`（在当前进度打标记，仅记录），
  摇头 → `onShakeDetected()` → `performMediaAction(REWIND, 5)`。
- 分类器为纯 Kotlin（时间由外部注入 `SystemClock.elapsedRealtime()`），11 条 JVM 单测覆盖。

**本地流式语音识别**（`service/asr/VoskSpeechService.kt`，默认后端）：

- AudioRecord 采集 **16kHz / 单声道 / PCM16**，100ms 一个读周期；
- 模型：`assets/models/vosk-model-small-cn-0.22/`（约 42MB，打包进 APK）；
  首次启动由**自实现的递归解包**（弃用 vosk 自带 StorageService——其回调跑在自带线程，
  抛异常会崩进程）拷到内部存储，全程 try/catch；
- 循环内 `Recognizer.acceptWaveForm(buffer, len)`；`result`（最终句）→ `onText` →
  指令解析；`partialResult`（半句）→ `onPartial` → 只刷新界面「最近识别」，
  **不喂指令解析**（避免半句话触发控制）；
- 交付结果后循环**不退出**，持续监听（有日志证明）；`stop/release` 幂等，
  异常退出强制复位 `isListening`（修复过"守卫误判导致语音永久失效"的死锁）。
- 兜底链：Vosk 失败 → `SystemAsrEngine`（自动重启式持续监听）→ 仍失败则界面显示原因，
  App 不崩。云端 `SiliconFlowAsrEngine` 仅当显式选择且 Key 存在时使用，
  失败一次即**静默**降级本地（无报错红字）。

**指令解析**（`shared/.../service/nlp/`）：

- 判定顺序（`SpeechCommandParser`）：回退（"回退/倒退/后退…"，缺数字默认 10 秒）→
  时间表达式（"15分钟/半小时/一个小时/一小时三十分钟/十五分钟"…）→ 暂停（"暂停/停一下…"）→
  继续（"继续/恢复/播放…"）→ 只说了"提醒"没时间 → 默认 10 分钟。
- `LocalCommandRouter.route(text, source)` 输出 `PlannedAction.Media / ScheduleTimer / Nothing`，
  全程本机计算、与 API Key 无关；`executeLocal` 统一处理语音与手动两条入口。

**定时提醒**（`service/ReminderScheduler.kt` + `ReminderReceiver.kt`）：

- 有精确闹钟权限 → `setExactAndAllowWhileIdle`；SecurityException 或未授权 →
  降级 `setAndAllowWhileIdle`（功能不至于失效）；
- `ReminderReceiver` 在 Manifest 注册（`exported=false`），到点后建通知渠道、发通知、
  更新状态；ColorOS 需用户放行后台运行，否则杀进程后闹钟可能被吞。

### 3.3 跨应用控制：MediaSession

**文件**：`service/MediaNotificationListener.kt`、`service/MediaSessionController.kt`、
`service/LingMouEngine.kt`（`performMediaAction`）。

- **前提**：通知使用权（`NotificationListenerService`）。只有已授权的监听组件所属应用
  才能 `MediaSessionManager.getActiveSessions(ComponentName)` 读到第三方会话。
  `connected` 标志跟踪监听服务的真实连接状态——**设置里显示已授权 ≠ 监听还连着**
  （ColorOS 重启/系统回收后常见断开，日志可一眼区分）。
- **每次指令全新获取、用完即弃**（`dispatch()`）：
  ① `getActiveSessions` 重新查询（打印返回数量与各会话"包名=状态"）；
  ② 重新选主会话：排除本应用 → 系统壳会话排后 → 优先 STATE_PLAYING → 可传输状态 → 首个；
  ③ `transportControls.pause()/play()/seekTo()` 下发，try/catch；
  ④ finally 丢弃引用（本类不缓存 Controller、不注册 callback，无 close 需求）；
  ⑤ 800ms 后 `verifyAfterDispatch` 复查目标包状态/位置是否真的变了
  （TransportControls 是异步 fire-and-forget，事后复查是唯一诚实的成功判据）。
- 命中日志全链可见：动作触发（含会话数）→ 下发前（含数量/授权/连接状态）→ 下发 → 下发后校验。

### 3.4 用户中心：本地数据库 + 权限管理

**数据库**（`shared/src/commonMain/sqldelight/.../db/`，5 张表，均自增主键）：

| 表 | 列（除 id 外） | 用途 |
| --- | --- | --- |
| `StudyNote` | title, content, mode, sourceTranscript, createdAt | 学习笔记 |
| `Recipe` | title, summary, sourceTranscript, createdAt | 菜谱 |
| `RecipeStep` | recipeId, stepIndex, instruction, durationHint | 菜谱步骤（无外键，清理顺序可控） |
| `FocusSession` | startedAt, endedAt, durationMs, distractionCount, noteId | 专注会话统计 |
| `DistractionRecord` | sessionId, happenedAt, awayMillis, faceCount, kind | 分心记录（gaze_away / intruder） |

- 自增 id 用连接级 `SELECT last_insert_rowid()`（`Meta.sq`）获取，兼容 API 26 的 SQLite 3.18
  （不用 `INSERT ... RETURNING`）。
- `KnowledgeRepository`（commonMain）提供全部 CRUD，无 Android 依赖；
  Android 侧 `DatabaseService` 用 `AndroidSqliteDriver` 接线并把行对象映射成
  `NoteItem/RecipeItem`（时间文本由平台侧 `java.time` 格式化后填入）；
  桌面侧 `JvmFileDatabaseFactory` 用 `PRAGMA user_version` 判断是否建表，落
  `%USERPROFILE%/.lingmou/lingmou.db`。
- 会话结算：模式切换即结算上一段（专注模式写 FocusSession + DistractionRecord），
  语音原文按会话累积成文字稿（手动模拟不计入）。
- 演示数据：`DatabaseService.seedDemo()`——仅在库为空时注入 3 条笔记 + 2 条菜谱
  （不覆盖真实数据）；「用户 → 我的笔记/食谱」空状态提供"一键注入演示数据"按钮。
- 单测：8 条 JVM 用例跑**真 SQLite 内存库**，覆盖五表读写、排序、关联删除。

**权限管理**（`ui/UserTab.kt` + `MainActivity`）：

- 运行时权限三行（摄像头/麦克风/通知）：显示状态，未授予可点"申请"重新弹系统授权；
- 特殊权限两行（通知使用权/精确闹钟）：显示状态 + "去开启"跳系统设置
  （通知使用权用 `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` +
  `EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`，ColorOS 的列表页不支持包名直达）；
- 状态实时刷新：`onResume` 刷新 + 700ms 延迟复检（系统设置回写有滞后）+ 监听服务
  连接/断开回调即时同步；
- 桌面端无此体系：显示"当前平台暂不支持 + 原因"，不留误导性空行。

---

## 4. 关键数据流与接口说明

### 4.1 语音从录音到触发控制的完整数据流

```
AudioRecord(16kHz/单声道/PCM16)
  → VoskSpeechService 循环: acceptWaveForm(buffer, len)
       ├─ partialResult ──> onPartial ──> AppUiState.lastTranscript（界面“最近识别”实时刷新）
       └─ result(最终句) ──> onText
            → LingMouEngine.onSpeechText
            → executeLocal（appendTranscript 会话文字稿 + caption 已移除）
            → LocalCommandRouter.route(text, source)
                 ├─ PlannedAction.Media      → performMediaAction(action, reason, seconds)
                 │     → MediaSessionController.dispatch()
                 │         ① getActiveSessions（全新查询，打印数量与包名=状态）
                 │         ② choosePrimary（PLAYING > transportable > 首个）
                 │         ③ transportControls.pause()/play()/seekTo()（try/catch）
                 │         ④ finally 丢引用；⑤ 800ms 后 verifyAfterDispatch 校验状态是否变化
                 │     → 无论成败，占位播放器同步反映动作
                 ├─ PlannedAction.ScheduleTimer → scheduleTimer
                 │     → AlarmManager.setExactAndAllowWhileIdle（降级 setAndAllowWhileIdle）
                 │     → ReminderReceiver 到点发通知
                 └─ PlannedAction.Nothing   → 仅日志
```

每一步都有 logcat 节点：`LingMouVosk`（识别结果/循环持续）、`LingMouSysAsr`（系统识别）、
`LingMouAsr`（云端）、`LingMouGesture`（头部动作）、`LingMouEngine`（解析与动作触发、
会话数量）、`LingMouMedia`（会话查询/下发/下发后校验）、`LingMouReminder`（定时）、
`LingMouFps`（检测帧率）。

### 4.2 权限申请流程

- **运行时权限**（摄像头/麦克风/通知）：`MainActivity.requestRuntimePermissions()`
  统一申请缺失项；结果回调同步到 `AppUiState`（cameraPermission/audioPermission/notificationsEnabled）。
- **通知使用权**（跨应用控制前提）：跳 `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`
  直达本应用监听组件页；授权后 `MediaNotificationListener.onListenerConnected` 即时刷新状态。
  注意：重启/系统回收后可能"开关在但连接断"，日志 `监听已连接=false` 可判，恢复办法是关掉重开。
- **精确闹钟**（Android 14 默认拒绝）：跳系统"闹钟和提醒"页；
  `canScheduleExactAlarms()` 每次设置提醒前实时查询；被撤销时自动降级非精确闹钟。
- **ColorOS 后台限制**：提醒要准点需用户手动放行自启动/后台运行（应用详情页）。

---

## 5. 运行与部署指南

### 5.1 环境要求

- JDK 17（本机路径 `C:\Users\wxf20\.jdks\jdk-17.0.2`）
- Android SDK（`platforms;android-36`、build-tools 35.0.0、platform-tools）
- Gradle 8.14.6（工程自带 `.toolchain/gradle-8.14.6`，由 `.toolchain/build.ps1` 驱动）
- 真机/模拟器：**minSdk 26（Android 8.0）**，compileSdk/targetSdk 36；
  已验证设备 OPPO A97 5G（Android 14）
- 仓库源已配置为官方源优先 + 阿里云镜像兜底（`settings.gradle.kts`），
  并移除了不可达的 gradlePluginPortal

### 5.2 本地配置

`local.properties`（已 gitignore，不会进仓库）：

```properties
sdk.dir=C:/Users/wxf20/AppData/Local/Android/Sdk

# 硅基流动（可选，云端高精度模式；全部可不填）
SILICONFLOW_API_KEY=sk-xxx
SILICONFLOW_ASR_MODEL=FunAudioLLM/SenseVoiceSmall
SILICONFLOW_ENDPOINT=https://api.siliconflow.cn/v1/audio/transcriptions
SILICONFLOW_LLM_MODEL=Qwen/Qwen2.5-7B-Instruct
SILICONFLOW_LLM_ENDPOINT=https://api.siliconflow.cn/v1/chat/completions
```

以上经 `app/build.gradle.kts` 的 `buildConfigField` 注入 `BuildConfig`（不打印 Key；
留空自动回退默认模型并在界面标注"默认值"）。

**Vosk 模型**：`app/src/main/assets/models/vosk-model-small-cn-0.22/`（约 42MB，
已 gitignore）。首次克隆后用
`python tools/download-vosk-model-parallel.py`（8 线程 Range 分段，官方源单连接限速约 10KB/s）
下载解压。运行时首次进入烹饪模式自动解包到应用内部存储（一次性，数十秒）。

### 5.3 编译与打包

```powershell
# Android Debug APK（输出 .toolchain/build-app/app/outputs/apk/debug/app-debug.apk）
powershell -File .toolchain\build.ps1 assembleDebug

# 单测（39 条，跑在 shared 的 JVM target）
powershell -File .toolchain\build.ps1 :shared:jvmTest

# 桌面端运行（Compose for Desktop，启动自检探针 [LINGMOU-PROBE]）
powershell -File .toolchain\build.ps1 :desktop:run

# 发布到桌面（固定覆盖 LingMou_Demo.apk，不产生新文件，SHA256 校验）
powershell -File tools\publish-demo-apk.ps1
```

构建环境特殊说明：受限沙箱下需 `GRADLE_USER_HOME` 指向工程内目录、
`--no-watch-fs`、`-Pkotlin.compiler.execution.strategy=in-process`，
并把 `layout.buildDirectory` 重定向到 `.toolchain/build-app`（init 脚本
`.toolchain/redirect-build-dir.gradle`）——这些都已封装进 `.toolchain/build.ps1`。

---

## 6. 已知限制与未来规划

### 6.1 已知限制

**延迟/体验**
- 视线暂停固定 3 秒确认期 + 2 秒动作冷却（防误触与防闪烁的刻意取舍）；
- 头部动作需要 1400ms 窗口内两个反向极值，动作要做得稍微明显；
- 语音"说完→出字"延迟 ≈ 一个 100ms 读缓冲周期 + Vosk 解码，但**最终句**才触发指令
  （半句只上屏不控制，避免误触发）。

**音频来源限制**
- 语音识别的输入是麦克风：只有**外放**的视频声音能被"听到"；戴耳机时听不到视频内容。
  真正的"音轨级"字幕需 AudioPlaybackCapture + MediaProjection，
  而 B 站/华为视频通常设置 `allowAudioPlaybackCapture=false`。

**跨应用控制**
- 目标 App 必须**正在播放且发布了 MediaSession**；部分 App（含 B 站某些版本）被暂停后
  会撤掉会话，导致下一次查询为空——这是 App 侧行为，日志可区分；
- 通知使用权在部分厂商系统（ColorOS）重启后可能"开关在但监听断开"，需关掉重开。

**Windows 桌面端**（`PlatformCapabilities.WINDOWS.unsupported` 逐条标注）：
人脸检测/视线（未接入 OpenCV）、麦克风语音（未接入）、跨应用媒体控制
（MediaSession 是 Android 专有 API，未引入 JNativeHook）、系统级定时
（ScheduledExecutorService 仅进程存活期间有效）、云端笔记/菜谱生成（未接 LLM 客户端）。
桌面端支持：同一套 UI、本地指令闭环（LocalCommandRouter）、SQLDelight 文件库、
占位播放器。

**其他**
- 定时提醒不持久化（进程被杀后丢失），依赖厂商后台放行；
- 云端 LLM（总结/菜谱抽取）与生成按钮已随 UI 精简移除，仅保留引擎方法。

### 6.2 V2.1 迭代方向

1. **云端大模型接入**：把 LLM 总结/菜谱抽取重新接入用户中心（会话结束后自动沉淀）；
2. **识别引擎可插拔**：sherpa-onnx / whisper.cpp 作为 Vosk 的可替换后端，
   统一在 `AsrEngine` 接口后；
3. **多设备协同**：手机采集、平板/桌面播放的联动（借助局域网信令 + 跨端 MediaSession 语义）；
4. **桌面端补全**：OpenCV 人脸/视线、SMTC/JNativeHook 媒体控制、
   Windows 任务计划实现系统级定时；
5. **体验调优**：视线暂停阈值自适应、头部动作参数用户可调、多语言界面。

---

## 附：仓库速览

```
LingMouApp/
├── app/          Android 壳（22 个 .kt）：引擎/前台服务/ASR 三引擎/媒体/提醒/权限跳转
├── shared/       KMP 模块（commonMain 32 个 .kt + jvmTest 4 个）：
│                 模型 / UI / 指令解析 / 手势分类 / SQLDelight 五表 / 仓库
├── desktop/      Compose for Desktop（main.kt + DesktopEngine.kt）
├── tools/        adb-install.ps1 / publish-demo-apk.ps1 / download-vosk-model*.py
├── .toolchain/   build.ps1（沙箱友好构建入口）/ 仓库与构建目录重定向
├── DEVELOPMENT.md（本文档）
└── README.md     逐步验证指南（真机验收第 0-10 步）
```

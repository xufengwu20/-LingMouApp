import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ============================================================
// 硅基流动云端 ASR 配置，全部从 local.properties 读取（都可不填）。
//
// 这里读不到任何东西都不影响 App 运行：
//   - 缺 Key      → 代码里走「自动降级到系统内置识别」分支
//   - 缺模型名    → 回退到 DEFAULT_ASR_MODEL，并在界面上标注「默认值」
//   - 本地指令闭环 → 与这些配置完全无关，连 INTERNET 权限都用不上
// ============================================================
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val siliconFlowKey: String = localProps.getProperty("SILICONFLOW_API_KEY").orEmpty().trim()

// 兼容旧字段名 SILICONFLOW_MODEL
val rawAsrModel: String = (
    localProps.getProperty("SILICONFLOW_ASR_MODEL")
        ?: localProps.getProperty("SILICONFLOW_MODEL")
    ).orEmpty().trim()

val DEFAULT_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
val asrModelIsDefault: Boolean = rawAsrModel.isBlank()
val siliconFlowModel: String = if (asrModelIsDefault) DEFAULT_ASR_MODEL else rawAsrModel

val siliconFlowEndpoint: String = localProps.getProperty("SILICONFLOW_ENDPOINT")
    .orEmpty().trim()
    .ifBlank { "https://api.siliconflow.cn/v1/audio/transcriptions" }

// 云端内容分析（LLM）用的模型与地址。同样可以不填。
val DEFAULT_LLM_MODEL = "Qwen/Qwen2.5-7B-Instruct"
val rawLlmModel: String = localProps.getProperty("SILICONFLOW_LLM_MODEL").orEmpty().trim()
val llmModelIsDefault: Boolean = rawLlmModel.isBlank()
val siliconFlowLlmModel: String = if (llmModelIsDefault) DEFAULT_LLM_MODEL else rawLlmModel

val siliconFlowLlmEndpoint: String = localProps.getProperty("SILICONFLOW_LLM_ENDPOINT")
    .orEmpty().trim()
    .ifBlank { "https://api.siliconflow.cn/v1/chat/completions" }

// 正常情况下 AGP 会用 ~/.android/debug.keystore 自动签名 debug 包。
// 当该目录不可写（受限沙箱 / CI）时，回退到工程内自带的调试签名，保证 assembleDebug 仍能出包。
val localDebugKeystore = rootProject.file(".toolchain/keystore/debug.keystore")
val useLocalDebugKeystore = localDebugKeystore.exists()

android {
    namespace = "com.example.lingmouapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.lingmouapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "SILICONFLOW_API_KEY", "\"$siliconFlowKey\"")
        buildConfigField("String", "SILICONFLOW_ASR_MODEL", "\"$siliconFlowModel\"")
        buildConfigField("boolean", "SILICONFLOW_ASR_MODEL_IS_DEFAULT", "$asrModelIsDefault")
        buildConfigField("String", "SILICONFLOW_ENDPOINT", "\"$siliconFlowEndpoint\"")
        buildConfigField("String", "SILICONFLOW_LLM_MODEL", "\"$siliconFlowLlmModel\"")
        buildConfigField("boolean", "SILICONFLOW_LLM_MODEL_IS_DEFAULT", "$llmModelIsDefault")
        buildConfigField("String", "SILICONFLOW_LLM_ENDPOINT", "\"$siliconFlowLlmEndpoint\"")
    }

    signingConfigs {
        if (useLocalDebugKeystore) {
            create("localDebug") {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (useLocalDebugKeystore) {
                signingConfig = signingConfigs.getByName("localDebug")
            }
        }
        release {
            isMinifyEnabled = false
            if (useLocalDebugKeystore) {
                signingConfig = signingConfigs.getByName("localDebug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 平台无关的数据/领域层（SQLDelight 生成代码 + 仓库）
    implementation(project(":shared"))
    // SQLite 驱动只在 Android 端需要；桌面/Windows 端换 JdbcSqliteDriver
    implementation(libs.sqldelight.android.driver)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.face.detection)

    // 本地离线语音识别（Vosk）。
    // 注意：vosk-android 是 Android AAR（内含各 ABI 的 libvosk.so），不是 KMP 构件，
    // 所以放在 :app 而不是 :shared —— 顺带绕开 KMP 共享模块加载原生库的问题。
    // 模型在 app/src/main/assets/models/（约 45MB，gitignore，用 tools/download-vosk-model.py 下载）。
    implementation("com.alphacephei:vosk-android:0.3.47")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // 指令解析是纯 Kotlin，可以在 JVM 上直接跑单元测试，不需要真机
    testImplementation(libs.junit)
}

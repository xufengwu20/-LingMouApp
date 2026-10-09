import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 可选：在 local.properties 里写 SILICONFLOW_API_KEY=sk-xxx 即可启用云端 ASR。
// 不填时应用自动回退到系统内置 SpeechRecognizer，功能依然可用。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val siliconFlowKey: String = localProps.getProperty("SILICONFLOW_API_KEY").orEmpty()
val siliconFlowModel: String =
    localProps.getProperty("SILICONFLOW_MODEL") ?: "FunAudioLLM/SenseVoiceSmall"

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
        buildConfigField("String", "SILICONFLOW_MODEL", "\"$siliconFlowModel\"")
        buildConfigField(
            "String",
            "SILICONFLOW_ENDPOINT",
            "\"https://api.siliconflow.cn/v1/audio/transcriptions\""
        )
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

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // 指令解析是纯 Kotlin，可以在 JVM 上直接跑单元测试，不需要真机
    testImplementation(libs.junit)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 顺序重要：先 AGP library，再 KMP —— 这是 KMP + Android 的官方推荐顺序
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    // Kotlin 2.x 的 Compose 编译器插件（版本跟随 Kotlin）
    id("org.jetbrains.kotlin.plugin.compose")
    // Compose Multiplatform：提供 compose.runtime/foundation/material3 这些依赖别名，
    // 并且在 Android 侧重定向到对应的 androidx 构件（不会重复打包）
    id("org.jetbrains.compose")
    id("app.cash.sqldelight")
}

// ============================================================
// :shared —— 平台无关的「数据 + 领域」层
//
// KMP 目标只有两个：
//   androidTarget() → 给 :app 用
//   jvm()           → 给 :desktop（以及未来的 Windows 包）用
//
// 之所以只有这两个：Web/iOS 不是当前目标，加上去只会让构建更容易坏。
// 唯一按平台定制的只有 SqlDriver（见 DatabaseDriverFactory）。
// ============================================================

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api：让 :app / :desktop 都能直接用到 SqlDriver、Query 等类型
            api(libs.sqldelight.runtime)

            // 共享 UI。CMP 的 material3/foundation 在 Android 侧会解析到
            // androidx 的同名构件，所以 :app 的 Compose BOM 不会被重复引入。
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }

        jvmMain.dependencies {
            // compileOnly：让 JvmDatabaseDriverFactory 能编译，但**不会**把
            // sqlite-jdbc（约 12MB 原生库）传递到 Android 的 APK 里
            compileOnly(libs.sqldelight.sqlite.driver)
        }

        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.example.lingmouapp.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

sqldelight {
    databases {
        create("LingMouDatabase") {
            packageName.set("com.example.lingmouapp.db")
            generateAsync.set(false)
            // 我们没有任何 .sqm 迁移文件，这个任务只会白拉起 sqlite-jdbc
            verifyMigrations.set(false)
        }
    }
}

tasks.withType<Test>().configureEach {
    // sqlite-jdbc 会把原生库解压到 java.io.tmpdir；受限环境里那里不可写，
    // 它会退回到 C:\Windows 然后抛 AccessDeniedException。
    // 显式指到工程构建目录内，并且**先把目录建出来**
    // （sqlite-jdbc 会直接在里面建 .lck 文件，目录不存在同样失败）。
    val sqliteTmpDir = layout.buildDirectory.dir("sqlite-tmp").get().asFile
    doFirst { sqliteTmpDir.mkdirs() }
    systemProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}

// SQLDelight 的 DSL 里 verifyMigrations=false 只跳过「校验」，任务本身照样执行，
// 而它内部会用 sqlite-jdbc 建临时 schema 库 —— 正好撞上原生库解压问题。
tasks.configureEach {
    if (name.startsWith("verify") && name.contains("Migration")) {
        enabled = false
    }
}

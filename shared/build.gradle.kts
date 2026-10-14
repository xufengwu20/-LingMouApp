import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 不带版本：版本由根工程 build.gradle.kts 的 alias(...) apply false 决定。
    // 带版本会与 kotlin.android 已经放到 classpath 上的 KGP 冲突。
    id("org.jetbrains.kotlin.jvm")
    id("app.cash.sqldelight")
}

// ============================================================
// :shared —— 平台无关的「数据 + 领域」模块
//
// 刻意做成**纯 Kotlin JVM 库**而不是 KMP：
//   - Android 端 (app) 和未来的 Windows 桌面端都可以直接依赖它；
//   - 不需要引入 KMP 的 source set / expect-actual 体系，构建复杂度低得多；
//   - 唯一需要各平台自己提供的只有 SqlDriver（见 DatabaseDriverFactory）。
// ============================================================

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sqldelight {
    databases {
        create("LingMouDatabase") {
            packageName.set("com.example.lingmouapp.db")
            // 生成的数据类统一带类型化的行对象，便于上层映射成 UI 模型
            generateAsync.set(false)
            // 我们没有任何 .sqm 迁移文件，这个任务只会白拉起 sqlite-jdbc，
            // 而它在受限环境下会因为原生库无法解压而失败。
            verifyMigrations.set(false)
        }
    }
}

dependencies {
    // api：让 app / 桌面端都能直接用到 SqlDriver、Query 等类型
    api(libs.sqldelight.runtime)

    // compileOnly + testImplementation：
    //   - compileOnly 让 JvmDatabaseDriverFactory 能编译，但**不会**把
    //     sqlite-jdbc（约 12MB 原生库）传递到 Android APK 里；
    //   - testImplementation 让 JVM 单测可以跑真 SQLite（内存库）。
    compileOnly(libs.sqldelight.sqlite.driver)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // sqlite-jdbc 会把原生库解压到 java.io.tmpdir；受限环境里那里不可写，
    // 它会退回到 C:\Windows 然后抛 AccessDeniedException。
    // 显式指到工程构建目录内（一定可写）。
    //
    // 注意：这个目录必须**先存在** —— sqlite-jdbc 会在里面直接建 .lck 文件，
    // 目录不存在时同样会失败（表现为 UnsatisfiedLinkError）。
    val sqliteTmpDir = layout.buildDirectory.dir("sqlite-tmp").get().asFile
    doFirst { sqliteTmpDir.mkdirs() }
    systemProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}

// SQLDelight 的 DSL 里 verifyMigrations=false 只跳过「校验」，任务本身照样执行，
// 而它内部会用 sqlite-jdbc 建一个临时 schema 库 —— 正好撞上原生库解压问题。
// 我们没有任何 .sqm 迁移文件，这个任务毫无意义，直接在 Gradle 层关掉。
tasks.configureEach {
    if (name.startsWith("verify") && name.contains("Migration")) {
        enabled = false
    }
}

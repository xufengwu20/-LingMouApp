import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// 注意：必须 import，不能写 java.util.zip.ZipFile ——
// 在 Gradle Kotlin DSL 里 `java` 指的是 JavaPluginExtension，不是包名。
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// ============================================================
// :desktop —— Windows 桌面端（Compose for Desktop）
//
// 复用 :shared 的全部内容：模型、SQLDelight 五张表与仓库、
// 本地指令闭环（LocalCommandRouter / 时间解析 / 手势分类器）、
// 以及**同一套 Compose 组件**（MainScreen / KnowledgeScreen / components）。
//
// 平台差异只有三处，都在这个模块里：
//   1. 数据库驱动 → JvmDatabaseDriverFactory（sqlite-jdbc）
//   2. 定时提醒   → ScheduledExecutorService（进程存活期间有效）
//   3. 媒体控制   → 桌面占位播放器（不做跨应用控制，见 DesktopEngine 注释）
// ============================================================

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.runtime)

    // 桌面端的 SQLite 驱动（:shared 里是 compileOnly，运行期必须由使用方提供）
    implementation(libs.sqldelight.sqlite.driver)
}

// skiko 的原生库解压目录。必须在配置期就建出来。
//
// 为什么需要它：skiko 初始化时会调
//     Files.createTempDirectory("skiko", <FileAttribute>)
// 在 java.io.tmpdir 下建临时目录再拷入原生库。受限环境里系统临时目录对 Java
// 子进程不可写，这一步会抛 java.io.IOException，App 连窗口都开不出来。
// 对照证据：sqlite-jdbc 能把原生库解压进工程目录，说明「Java 子进程写工作区」是可行的。
val jvmTmpDir = layout.buildDirectory.dir("jvm-tmp").get().asFile.apply { mkdirs() }

// ---------------------------------------------------------------------------
// skiko 原生库的准备工作
//
// 为什么需要这一整段：skiko 初始化时会调
//     Files.createTempDirectory("skiko", <FileAttribute>)
// 在 java.io.tmpdir 下建临时目录，再把 skiko-windows-x64.dll 拷进去。
// 受限沙箱会拦掉「建目录」这一步，App 直接抛 java.io.IOException 退出，
// 连窗口都开不出来。
//
// （已排除的可能：不是「目录不存在」也不是「不可写」—— 同一个 java.io.tmpdir 里，
//   sqlite-jdbc 成功解压出了它的 .dll，说明工作区对 Java 子进程是可写的。
//   被拦的是 Files.createTempDirectory 这个特定调用。）
//
// 解法：绕过解压流程。skiko 支持用 -Dskiko.library.path 直接指定库所在目录，
// 我们就在构建期把原生库和 ICU 数据从依赖 jar 里解出来。
// 这同时也让 App 在企业的「临时目录被锁死」环境里能正常启动。
// ---------------------------------------------------------------------------
val skikoNativeDir = layout.buildDirectory.dir("skiko-native").get().asFile

val extractSkikoNative by tasks.registering {
    description = "把 skiko 的原生库与 ICU 数据从依赖 jar 解到工程内，供 -Dskiko.library.path 使用"
    val runtimeCp = configurations.named("runtimeClasspath")
    val outDir = skikoNativeDir
    inputs.files(runtimeCp)
    outputs.dir(outDir)

    doLast {
        outDir.mkdirs()
        val wanted = setOf("skiko-windows-x64.dll", "icudtl.dat")
        val found = mutableSetOf<String>()
        // 用普通 for 循环而不是 forEach + return@label：
        // 嵌套 forEach 的标签极易写错（label 只能挂在 lambda 上），
        // 这里有过一次「标签贴错导致整个构建脚本编译失败」的教训。
        for (jar in runtimeCp.get()) {
            if (!jar.name.contains("skiko")) continue
            ZipFile(jar).use { zf ->
                val entries = zf.entries().asSequence()
                    .filter { it.name.substringAfterLast('/') in wanted }
                for (entry in entries) {
                    val shortName = entry.name.substringAfterLast('/')
                    val target = File(outDir, shortName)
                    // 已存在且大小一致就跳过重写：正在运行的桌面实例会占用这个 dll，
                    // 直接覆盖会抛 FileNotFoundException（文件被另一个进程使用）。
                    if (target.exists() && target.length() == entry.size) {
                        found += shortName
                        continue
                    }
                    zf.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    found += shortName
                }
            }
        }
        println("[skiko] 已解出: ${found.joinToString()} -> ${outDir.absolutePath}")
    }
}

compose.desktop {
    application {
        mainClass = "com.example.lingmouapp.desktop.MainKt"

        // 注意：必须走 CMP 官方的 jvmArgs。用 tasks.withType<JavaExec> 设 jvmArgs
        // 会被 compose 插件在之后覆盖掉，看起来「配置生效了」（目录被创建）但参数没传进去，
        // 极易误判成权限问题。
        jvmArgs += listOf(
            "-Djava.io.tmpdir=${jvmTmpDir.absolutePath}",
            "-Dskiko.library.path=${skikoNativeDir.absolutePath}",
            "-Dskiko.data.path=${skikoNativeDir.absolutePath}"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "LingMouApp"
            packageVersion = "1.0.0"
            description = "灵眸 · 专注 / 烹饪 / 勿扰 三模式助手（Windows 桌面端）"
            vendor = "LingMouApp"
        }
    }
}

// 开发期跑之前先把原生库准备好
tasks.matching { it.name == "run" || it.name.startsWith("run") || it.name.startsWith("package") }
    .configureEach { dependsOn(extractSkikoNative) }

tasks.withType<Test>().configureEach {
    // 与 :shared 同样的原因：sqlite-jdbc 解压原生库需要预先存在的可写目录
    val sqliteTmpDir = layout.buildDirectory.dir("sqlite-tmp").get().asFile
    doFirst { sqliteTmpDir.mkdirs() }
    systemProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}

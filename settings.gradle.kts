pluginManagement {
    repositories {
        // 顺序很重要：**官方源在前**。
        // 阿里云镜像本机出现过 DNS 解析瞬时失败，而 Gradle 遇到网络错误
        // 不会优雅降级到下一个仓库，会直接让整个解析失败。
        // 所以把已验证稳定可达的 google()/mavenCentral() 放在最前面。
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 故意不放 gradlePluginPortal()：
        // plugins.gradle.org 在本机曾 TLS 握手失败，而它排在最后，
        // 任何一次「前面的仓库都没有」都会退化成长时间超时重试（表现为构建卡死）。
        // google() + mavenCentral() + 三个阿里云镜像已覆盖 AGP / Kotlin / SQLDelight。
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "LingMouApp"
include(":app")
// 平台无关的数据/领域层：Android 与 Windows 桌面端共用
include(":shared")
// Windows 桌面端（Compose for Desktop）
include(":desktop")

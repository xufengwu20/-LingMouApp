// Top-level build file. Plugins are declared here with `apply false` so the
// versions live in exactly one place (gradle/libs.versions.toml).
//
// 注意 kotlin.jvm 也要在这里声明：
//   kotlin.android 已经把 kotlin-gradle-plugin 放上了构建 classpath，
//   如果 :shared 再用**带版本**的 alias 请求 kotlin.jvm，Gradle 会因为
//   「同一个插件已在 classpath 上、版本未知」而直接报错。
//   正确做法是根工程 apply false（定版本），子模块用不带版本的 id()。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

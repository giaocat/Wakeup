plugins {
    // 版本在这里统一声明（apply false），各模块再用 alias 应用：
    // 同一个 AGP 里的 application / library 插件如果在两个模块里各写一次版本，
    // Gradle 会报 "plugin is already on the classpath with an unknown version"。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

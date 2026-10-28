import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 自带 Kotlin 支持，无需再单独应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 手机端（:app）与手表端（:wear）共用的数据层。
 *
 * 这里放教务系统抓取 / 解析、领域模型、本地缓存、设置存储和作息时间表，
 * 两个端只是界面不同，数据与规则完全一致 —— 课表解析这种逻辑绝不能抄两份。
 *
 * 包里仍然用 `com.wakeup.data.* / com.wakeup.domain.* / com.wakeup.util.*`，
 * 所以从 :app 挪出来时上层 import 一行都不用改。
 */
android {
    namespace = "com.wakeup.core"
    compileSdk = 36

    defaultConfig {
        // 与 :app 保持一致（minSdk 23），保证两端可用的 Java/Android API 相同
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 本模块直接使用 java.time，minSdk 21 时同样要靠 desugaring
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

dependencies {
    // 上层（:app / :wear）会在自己的代码里直接写 suspend / Flow，
    // 所以协程对外暴露成 api，避免每个模块都重复声明。
    api(libs.kotlinx.coroutines.android)

    // java.time 在低版本上的实现
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
}

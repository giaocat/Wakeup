import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

/**
 * Wear OS 版（手表端）。
 *
 * 和手机端共享 `:core` 的数据层，界面完全是另一套：手表的屏幕是圆的、很小，
 * 所以用 Compose for Wear OS 的 Material 3：`AppScaffold` + `ScreenScaffold` +
 * `ScalingLazyColumn`（内容随滚动缩放，贴边不会被圆屏裁掉）、`TimeText`（顶部弧形时间）、
 * 横向翻页切换星期、圆形翻页指示器。
 *
 * 包名与手机端一致（`com.wakeup`），这样应用商店/系统能识别成同一个应用的两个端。
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wakeup.wear"
    // 与 :app 一致：编译/目标都对齐当前稳定平台 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wakeup"
        // 26：Compose for Wear OS 允许 25，但「表盘复杂功能」依赖
        // （androidx.wear.watchface:watchface-complications-data:1.3.0）硬性要求 26，
        // 而表盘小组件是本版本的核心功能，所以取 26（Wear OS 2.0 起）。
        // 实际体验仍以 Wear OS 3（API 30）为准。
        minSdk = 26
        targetSdk = 37
        // 与手机端共用一处版本定义（gradle/libs.versions.toml）
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
    }

    /** 签名配置与手机端同一套：环境变量优先（CI 用），其次 gradle 属性（本地用）。 */
    fun signingValue(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

    val releaseKeystoreFile = signingValue("KEYSTORE_FILE")?.let { file(it) }
        ?: signingValue("KEYSTORE_BASE64")?.let { encoded ->
            val target = layout.buildDirectory.file("release-keystore.jks").get().asFile
            target.parentFile.mkdirs()
            target.writeBytes(Base64.getMimeDecoder().decode(encoded))
            target
        }
    val releaseKeystorePassword = signingValue("KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingValue("KEY_ALIAS") ?: "wakeup"
    val releaseKeyPassword = signingValue("KEY_PASSWORD") ?: releaseKeystorePassword

    val hasReleaseSigning = releaseKeystoreFile != null &&
        releaseKeystorePassword != null &&
        releaseKeystoreFile.exists()

    // 与 :app 同一个约定：发布流程显式传 -PrequireSigning=true，缺密钥就直接失败
    val requireSigning = providers.gradleProperty("requireSigning")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)
        .get()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    afterEvaluate {
        if (hasReleaseSigning) {
            // 有固定密钥，什么也不用做
        } else if (requireSigning) {
            throw GradleException(
                "手表端要求固定签名密钥，但没有找到：" +
                    "请配置 KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD。",
            )
        } else {
            logger.warn("⚠ 手表端未配置发布密钥，release 将使用调试密钥签名，请勿分发。")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 与手机端一致：java.time 靠 desugaring 在 minSdk 25 上可用
        isCoreLibraryDesugaringEnabled = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":core"))

    // java.time 在 minSdk 25 上的实现
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // 从手机端接收课表（Wearable Data Layer）
    implementation(libs.play.services.wearable)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose 本体走 BOM；Wear 组件自身版本见 libs.versions.toml 的 wearCompose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // 表盘复杂功能（表盘小组件）与信息块（Tile）
    implementation(libs.wear.watchface.complications.data.source.ktx)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

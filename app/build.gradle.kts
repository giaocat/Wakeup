import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 自带 Kotlin 支持，无需再单独应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wakeup"
    // 36 是 Google 公开 SDK 仓库当前可获取的最高平台；
    // android-37 仍属 preview 频道，CI 上无法通过 sdkmanager 安装
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wakeup"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 「检查更新」用的仓库信息：读取 GitHub Releases 判断是否有新版本
        buildConfigField("String", "GITHUB_REPO", "\"giaocat/Wakeup\"")
        buildConfigField(
            "String",
            "RELEASES_API_URL",
            "\"https://api.github.com/repos/giaocat/Wakeup/releases/latest\"",
        )
        buildConfigField(
            "String",
            "RELEASES_PAGE_URL",
            "\"https://github.com/giaocat/Wakeup/releases\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 用 Android SDK 自动生成的默认调试密钥签名，使 release 产物可直接安装
            //（未配置签名时只会产出 app-release-unsigned.apk）。
            // 注意：debug keystore 不适合上架应用商店，且换机器/换 CI 后密钥会变，
            // 届时无法覆盖安装旧版本。正式分发请换成自己的 keystore。
            signingConfig = signingConfigs.getByName("debug")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    // Material 3 Expressive
    implementation(libs.m3)
    implementation(libs.m3.sizing)
    implementation(libs.m3.adaptive.core)

    implementation(libs.retrofit)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

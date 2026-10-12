import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

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
        versionCode = 2
        versionName = "1.0.1"

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
        // atom feed：网页资源，不受 API 的匿名限流影响，是更新检测的首选
        buildConfigField(
            "String",
            "RELEASES_ATOM_URL",
            "\"https://github.com/giaocat/Wakeup/releases.atom\"",
        )
    }

    /**
     * 取签名配置：环境变量优先（CI 用），其次 gradle 属性（本地用）。
     *
     * 环境变量名与 GitHub Actions 里配的一致：KEYSTORE_FILE / KEYSTORE_BASE64 /
     * KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD。
     */
    fun signingValue(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }


    // 固定发布密钥：保证每次构建、每台机器签出来的证书一致，用户才能覆盖安装。
    // 没提供时回退到 SDK 自动生成的调试密钥（仅供本地开发）。
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

    // CI 上必须用固定密钥：否则每次 runner 都会生成新的调试密钥，
    // 证书随之变化，用户无法覆盖安装 —— 这种情况直接让构建失败，不要静默产出。
    val isCi = !System.getenv("CI").isNullOrBlank()

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
            // 固定发布密钥（signingConfigs 已在上方配置好）
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else if (isCi) {
                // CI 上必须用固定密钥：否则每次 runner 都会生成新的调试密钥，
                // 证书随之变化，用户无法覆盖安装 —— 直接失败，不要静默产出。
                throw GradleException(
                    "CI 构建缺少签名密钥：请配置 KEYSTORE_BASE64 / KEYSTORE_PASSWORD / " +
                        "KEY_ALIAS / KEY_PASSWORD 四个 Secret。" +
                        "否则每次 CI 都会生成新的调试密钥，用户无法覆盖安装。",
                )
            } else {
                logger.warn(
                    "⚠ 未配置发布密钥，release 将使用调试密钥签名。" +
                        "调试密钥因机器而异，产物无法覆盖安装其它签名版本的安装包，请勿分发。",
                )
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

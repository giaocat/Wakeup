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
        // 23 = Android 6.0：Compose 1.11（animation-core）要求的最低值就是 23，
        // 再往下系统组件就不支持了。java.time 靠 core library desugaring 下放，见 compileOptions
        minSdk = 23
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.8"

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

    // 是否强制要求固定密钥。由发布流程显式传入：
    //   ./gradlew assembleRelease -PrequireSigning=true
    // 不用 "CI 环境变量 + assembleRelease" 之类的推断 —— 构建脚本在配置阶段拿不到
    // 任务图，无法判断这次到底是不是在打 release，误判会把 assembleDebug 一起干掉。
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
            // 固定发布密钥（signingConfigs 已在上方配置好）。
            // 缺密钥时的处理放在文件末尾的 afterEvaluate —— android { } 的 lambda
            // 是立即求值的，写在这里会连 assembleDebug 一起干掉。
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

// 缺密钥时的处理必须延迟到 afterEvaluate：android { } 里的 lambda 会**立即**求值，
// 若把检查写在 release { } 里，连 assembleDebug 都会失败（CI 上已踩到这个坑）。
// 而 afterEvaluate 是项目级钩子、拿不到任务图，所以用 -PrequireSigning=true 显式声明
// 「这次构建必须用固定密钥」—— 只有发布流程会传这个参数。
afterEvaluate {
    if (hasReleaseSigning) {
        // 有固定密钥，什么也不用做
    } else if (requireSigning) {
        throw GradleException(
            "本次构建要求固定签名密钥，但没有找到：" +
                "请配置 KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD " +
                "四个 Secret。否则每次构建都会生成新的调试密钥，用户无法覆盖安装。",
        )
    } else {
        logger.warn(
            "⚠ 未配置发布密钥，release 将使用调试密钥签名。" +
                "调试密钥因机器而异，产物无法覆盖安装其它签名版本的安装包，请勿分发。",
        )
    }
}

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 21 < 26：java.time（LocalDate / ZoneId / DateTimeFormatter…）
        // 由 desugar_jdk_libs 提供实现，否则 21–25 上会 NoClassDefFoundError
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
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }
}

dependencies {
    // 数据层（抓取 / 解析 / 缓存 / 作息规则），手机端与手表端共用
    implementation(project(":core"))

    // java.time 在 minSdk 21–25 上的实现
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // 把手表需要的课表推给手表（Wearable Data Layer）
    implementation(libs.play.services.wearable)

    // 大屏 / 折叠屏：WindowSizeClass + FoldingFeature
    implementation(libs.androidx.window)

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
    // 注：m3-adaptive 没有用到，且它把 minSdk 顶到了 24，
    // 为了把手机端压到 21 这里就不引了（需要时再加回来并同步抬 minSdk）。

    implementation(libs.retrofit)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Smartspacer：「下一节课」Target（清单里用 tools:overrideLibrary 处理它的 minSdk 29）
    implementation(libs.smartspacer.sdk.plugin)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Wakeup"

/** 手机端（Material 3 Expressive）。 */
include(":app")
/** 手表端（Wear OS Compose）。 */
include(":wear")
/** 手机端 / 手表端共用的数据层：抓取、解析、缓存、作息规则。 */
include(":core")

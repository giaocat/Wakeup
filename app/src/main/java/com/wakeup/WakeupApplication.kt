package com.wakeup

import android.app.Application
import android.content.Context
import com.wakeup.data.cache.TimetableCache
import com.wakeup.data.remote.TimetableRemoteDataSource
import com.wakeup.data.repository.SettingsRepository
import com.wakeup.data.repository.TimetableRepository

/**
 * 应用级依赖容器。
 *
 * 项目规模不大，这里用手写的 service locator 代替 DI 框架，减少构建复杂度。
 */
class WakeupApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 依赖容器。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    val timetableRepository: TimetableRepository = TimetableRepository(
        remote = TimetableRemoteDataSource(),
        cache = TimetableCache(appContext),
        onCacheChanged = { settingsRepository.notifyCacheChanged() },
    )
}

/** 便捷取用容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as WakeupApplication).container

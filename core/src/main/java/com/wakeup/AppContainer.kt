package com.wakeup

import android.content.Context
import com.wakeup.data.cache.TimetableCache
import com.wakeup.data.remote.TimetableRemoteDataSource
import com.wakeup.data.repository.SettingsRepository
import com.wakeup.data.repository.TimetableRepository

/**
 * 应用级依赖容器。
 *
 * 项目规模不大，这里用手写的 service locator 代替 DI 框架，减少构建复杂度。
 *
 * 手机端（`:app`）与手表端（`:wear`）**共用这份数据层**：设置、缓存、抓取解析全在 `:core` 里，
 * 两个端只是界面不同。进程内只创建一次，见 [AppContainerHolder]。
 */
class AppContainer(context: Context) {

    /** 应用上下文。手机端推课表给手表时需要它去拿 Wearable DataClient。 */
    val appContext: Context = context.applicationContext

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    val timetableRepository: TimetableRepository = TimetableRepository(
        remote = TimetableRemoteDataSource(),
        cache = TimetableCache(appContext),
        onCacheChanged = { settingsRepository.notifyCacheChanged() },
    )
}

/**
 * 容器持有者。
 *
 * 之所以不放在 `Application` 子类里：手表端不需要自己的 Application 类，
 * 而 `Context.appContainer` 又必须两端都能用。这里用进程内单例，谁先访问谁创建。
 */
object AppContainerHolder {

    @Volatile
    private var instance: AppContainer? = null

    fun get(context: Context): AppContainer =
        instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
}

/** 便捷取用容器（手机端与手表端都可用）。 */
val Context.appContainer: AppContainer
    get() = AppContainerHolder.get(this)

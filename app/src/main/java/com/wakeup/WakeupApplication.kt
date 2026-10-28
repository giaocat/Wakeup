package com.wakeup

import android.app.Application

/**
 * 手机端 Application。
 *
 * 依赖容器已经挪到 `:core`（见 [AppContainerHolder]），这样手表端也能复用同一份数据层，
 * 不需要再写一个几乎一样的 Application 子类。这里只负责把容器提前建好。
 */
class WakeupApplication : Application() {

    val container: AppContainer
        get() = appContainer

    override fun onCreate() {
        super.onCreate()
        // 提前初始化，避免首帧在主线程里建 DataStore/OkHttp
        AppContainerHolder.get(this)
    }
}

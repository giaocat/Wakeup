package com.wakeup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.wakeup.ui.WakeupApp
import com.wakeup.ui.WakeupViewModel
import com.wakeup.ui.theme.WakeupTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WakeupViewModel by viewModels {
        WakeupViewModel.factory(appContainer)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 窗口尺寸类只在 Activity 边界算一次（官方推荐做法），再向下传给各个页面。
            // 它内部读 LocalConfiguration，所以旋转、分屏、折叠导致的窗口变化会触发重组并重算。
            val windowSizeClass = calculateWindowSizeClass(this@MainActivity)

            // 折叠姿态：WindowInfoTracker 的流会随铰链展开/合上持续发射新值。
            // 初始值用 null（= 没有折叠信息）；不要自己 new WindowLayoutInfo ——
            // 它的构造函数带 @RestrictedApi，只允许 androidx.window 内部调用（lint 会直接报错）。
            val windowLayoutInfo: WindowLayoutInfo? by remember {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
            }.collectAsStateWithLifecycle(initialValue = null)

            val settings by viewModel.settings.collectAsStateWithLifecycle()
            WakeupTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                WakeupApp(
                    viewModel = viewModel,
                    windowSizeClass = windowSizeClass,
                    windowLayoutInfo = windowLayoutInfo,
                )
            }
        }
    }
}

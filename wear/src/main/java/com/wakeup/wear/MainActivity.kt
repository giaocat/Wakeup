package com.wakeup.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeup.appContainer
import com.wakeup.wear.ui.WearApp
import com.wakeup.wear.ui.theme.WakeupWearTheme

/**
 * 手表端唯一入口。
 *
 * 没有启动页、没有导航库：一个 Activity + Compose，`WearApp` 内部用状态切换几个屏幕。
 * 手表应用启动要快，层级越少越好。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearViewModel by viewModels {
        WearViewModel.factory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WakeupWearTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                WearApp(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onSelectWeek = viewModel::selectWeek,
                    onSelectDay = viewModel::selectDay,
                    onSaveToken = viewModel::saveToken,
                    onClearCache = viewModel::clearCache,
                )
            }
        }
    }
}

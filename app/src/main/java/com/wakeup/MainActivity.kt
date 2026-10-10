package com.wakeup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeup.ui.WakeupViewModel
import com.wakeup.ui.WakeupApp
import com.wakeup.ui.theme.WakeupTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WakeupViewModel by viewModels {
        WakeupViewModel.factory(appContainer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            WakeupTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                WakeupApp(viewModel = viewModel)
            }
        }
    }
}

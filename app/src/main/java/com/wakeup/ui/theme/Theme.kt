package com.wakeup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.wakeup.data.repository.ThemeMode

/**
 * 应用主题。
 *
 * 使用 Material 3 Expressive（M3E）的 [MaterialExpressiveTheme]：
 * 它会启用 expressive 字重/形状，并把 [MotionScheme.expressive] 作为默认动效方案，
 * 因此导航栏、列表、底部面板等组件都会带上 M3E 的弹性动效。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WakeupTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialExpressiveTheme(
        colorScheme = wakeupColorScheme(darkTheme = darkTheme, dynamicColor = dynamicColor),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

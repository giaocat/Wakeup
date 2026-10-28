package com.wakeup.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * 手表端主题。
 *
 * 直接用 Compose for Wear OS 的 Material 3 默认配色（深色、高对比，符合手表使用场景），
 * 不做动态取色：手表的系统取色在 Wear OS 3/4 上支持度有限，而深色底 + 亮字对 OLED 更省电。
 */
@Composable
fun WakeupWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme(), content = content)
}

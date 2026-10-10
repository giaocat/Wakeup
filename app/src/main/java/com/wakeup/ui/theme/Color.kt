package com.wakeup.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/**
 * Wakeup 的配色。
 *
 * Android 12 及以上默认使用系统动态取色；低版本回落到以校色蓝为种子的手工配色。
 */

// 主色种子：沈工大蓝
private val Seed = Color(0xFF1E6FD9)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1E6FD9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF0F7A6C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F1E4),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF8B4A9C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FF),
    onTertiaryContainer = Color(0xFF340043),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00458C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF9CD5C8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB8F1E4),
    tertiary = Color(0xFFDCBCE5),
    onTertiary = Color(0xFF512260),
    tertiaryContainer = Color(0xFF6A3A79),
    onTertiaryContainer = Color(0xFFF9D8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
)

@Composable
fun wakeupColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (dynamicColor && supportsDynamic) {
        val context = LocalContext.current
        return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (darkTheme) DarkColors else LightColors
}

/** 课程格子的配色（与周次状态无关，仅用于区分不同课程）。 */
val CoursePalette: List<Color> = listOf(
    Color(0xFF7C4DFF),
    Color(0xFFE53935),
    Color(0xFFD81B60),
    Color(0xFF3949AB),
    Color(0xFF00897B),
    Color(0xFFF4511E),
    Color(0xFFF9A825),
    Color(0xFF00838F),
)

fun courseColorFor(courseId: String): Color {
    if (courseId.isEmpty()) return CoursePalette.first()
    val hash = courseId.fold(0) { acc, char -> (acc * 31 + char.code) and 0x7FFFFFFF }
    return CoursePalette[hash % CoursePalette.size]
}

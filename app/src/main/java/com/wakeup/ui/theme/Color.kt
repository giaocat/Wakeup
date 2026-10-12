package com.wakeup.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.wakeup.domain.TimetableCourse
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

/** 色相组：7 个色相，相邻相差约 51°，保证任意相邻两组都能分辨。 */
internal val CourseHues: List<Float> = listOf(0f, 51f, 103f, 154f, 206f, 257f, 309f)

/** 每档的 (饱和度, 亮度)，从深到浅。 */
internal val CourseTones: List<Pair<Float, Float>> = listOf(
    0.72f to 0.34f, // 深：白字清晰
    0.78f to 0.46f, // 中
    0.82f to 0.58f, // 浅
    0.70f to 0.66f, // 更浅：深色主题下不与背景糊在一起
)

/**
 * 课程格子配色的十六进制槽位，共 7×4 = 28 个。
 *
 * 槽位顺序**按档位交错**：[色相0·深, 色相1·深, …, 色相6·深, 色相0·中, …]，
 * 因此按下标依次取用时，相邻课程永远来自不同色相组 —— 课表里挨着的两门课
 * 颜色一定明显不同（分配逻辑见 [courseColorsFor]）。
 *
 * 这里输出 ARGB 十六进制而非直接 `Color.hsl(...)`，是为了让配色在纯 JVM
 * 单元测试里也能校验（`Color.hsl` 依赖 Android 运行时的 `android.graphics.Color`）。
 */
internal fun courseColorHexSlots(): List<Long> = buildList {
    for ((saturation, lightness) in CourseTones) {
        for (hue in CourseHues) add(hslToArgb(hue, saturation, lightness))
    }
}

val CourseColorSlots: List<Color> = courseColorHexSlots().map { Color(it) }

/**
 * 按课表顺序给每门课分配颜色。
 *
 * 同一门课（courseId 相同）复用同一颜色；不同课按出现顺序依次取槽位。
 * 返回 courseId -> Color。
 */
fun courseColorsFor(courses: List<TimetableCourse>): Map<String, Color> {
    val slots = courseColorHexSlots()
    val result = LinkedHashMap<String, Color>()
    var next = 0
    for (course in courses) {
        if (course.courseId in result) continue
        result[course.courseId] = Color(slots[next % slots.size])
        next++
    }
    return result
}

/** 单个课程的颜色；散列定位，同一课程始终同色（不保证相邻课程不同色）。 */
fun courseColorFor(courseId: String): Color {
    val slots = courseColorHexSlots()
    if (courseId.isEmpty()) return Color(slots.first())
    var hash = 7
    for (char in courseId) hash = (hash * 31 + char.code) and 0x7FFFFFFF
    return Color(slots[hash % slots.size])
}

/** 深色背景下给格子加描边，进一步拉开相邻色块的边界。 */
fun courseOutlineFor(color: Color): Color = Color(
    red = color.red * 0.55f,
    green = color.green * 0.55f,
    blue = color.blue * 0.55f,
    alpha = 1f,
)

/** HSL -> 不透明 ARGB（0xFFRRGGBB）。纯函数，便于单元测试。 */
internal fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f / 60f
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    fun channel(value: Float): Long =
        (kotlin.math.round((value + m) * 255f).coerceIn(0f, 255f)).toLong()
    return 0xFF000000L or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}


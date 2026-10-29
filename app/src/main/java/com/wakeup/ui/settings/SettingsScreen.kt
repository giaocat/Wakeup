package com.wakeup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wakeup.BuildConfig
import com.wakeup.data.remote.WakeupApiFactory
import com.wakeup.data.repository.ThemeMode
import com.wakeup.data.repository.TimetableSourceKind
import com.wakeup.data.repository.UserSettings
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.adaptive.AdaptiveWindowInfo
import com.wakeup.ui.adaptive.contentMaxWidth
import com.wakeup.ui.components.UpdateRow
import com.wakeup.util.TimeUtils

/**
 * 设置页。
 *
 * 版式遵循 Material 3 设置页的既有做法：
 * - 顶栏是 [TopAppBar] + 返回箭头；
 * - 内容用 [LazyColumn]，每个分组由「labelLarge 分组标题 + [ListItem] 行 + [HorizontalDivider]」组成；
 * - 开关行用 `ListItem(trailingContent = { Switch(...) })`，整行可切换（toggleable + Role.Switch）；
 * - 主题模式是单选项列表（selectable + Role.RadioButton）。
 *
 * 自适应（[adaptive]）：
 * - 正文收窄到 840dp 并水平居中，1000dp 以上的窗口不会出现「单栏列表拉满整屏」；
 * - Expanded 宽度下分组排成两列（[Row] + 两个带 weight 的 [Column]），一屏放下更多设置项。
 *
 * 链接编辑不放在行内输入框里，而是点「修改」后弹 [AlertDialog]，避免设置页出现表单式排版。
 */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    state: ScheduleUiState,
    cacheSizeBytes: Long,
    onTimetableUrlChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onSourceKindChange: (TimetableSourceKind) -> Unit,
    onZfLogin: () -> Unit,
    onZfLogout: () -> Unit,
    onZfTermStartChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onHideUnscheduledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onBack: () -> Unit,
    updateChecking: Boolean = false,
    updateError: String? = null,
    latestVersion: String? = null,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
    adaptive: AdaptiveWindowInfo = AdaptiveWindowInfo(),
) {
    var editingTimetableUrl by rememberSaveable { mutableStateOf(false) }
    var editingBaseUrl by rememberSaveable { mutableStateOf(false) }
    var editingZfTermStart by rememberSaveable { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回课表",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SettingsContent(
                settings = settings,
                state = state,
                cacheSizeBytes = cacheSizeBytes,
                twoColumns = adaptive.isExpandedWidth,
                onEditZfTermStart = { editingZfTermStart = true },
                onZfLogin = onZfLogin,
                onZfLogout = onZfLogout,
                onEditTimetableUrl = { editingTimetableUrl = true },
                onEditBaseUrl = { editingBaseUrl = true },
                onSourceKindChange = onSourceKindChange,
                onThemeModeChange = onThemeModeChange,
                onDynamicColorChange = onDynamicColorChange,
                onHideUnscheduledChange = onHideUnscheduledChange,
                onRefresh = onRefresh,
                onClearCache = { confirmClearCache = true },
                updateChecking = updateChecking,
                updateError = updateError,
                latestVersion = latestVersion,
                onCheckUpdate = onCheckUpdate,
                onOpenRelease = onOpenRelease,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .contentMaxWidth()
                    .fillMaxHeight(),
            )
        }
    }

    if (editingTimetableUrl) {
        TextInputDialog(
            title = "课表链接 / token",
            label = "课表链接或 token",
            initialValue = settings.timetableUrl,
            placeholder = WakeupApiFactory.TIMETABLE_URL_EXAMPLE,
            supportingText = "粘贴课表分享链接；也可以只填 token（如 100000）。",
            confirmText = "保存并同步",
            onDismiss = { editingTimetableUrl = false },
            onConfirm = { value ->
                editingTimetableUrl = false
                onTimetableUrlChange(value)
            },
        )
    }

    if (editingBaseUrl) {
        TextInputDialog(
            title = "服务地址",
            label = "服务地址（token 模式使用）",
            initialValue = settings.baseUrl,
            placeholder = WakeupApiFactory.DEFAULT_BASE_URL,
            supportingText = "只填 token 时，会拼成「服务地址/user/kb/table/token/<token>」。",
            confirmText = "保存",
            onDismiss = { editingBaseUrl = false },
            onConfirm = { value ->
                editingBaseUrl = false
                onBaseUrlChange(value)
            },
        )
    }

    if (editingZfTermStart) {
        TextInputDialog(
            title = "开学第一周周一",
            label = "日期（YYYY-MM-DD）",
            initialValue = settings.zfTermStartDate,
            placeholder = "2026-08-24",
            supportingText = "用来把「第几周」换算成真实日期。留空则按课表页面里的「当前周」反推。",
            confirmText = "保存并同步",
            onDismiss = { editingZfTermStart = false },
            onConfirm = { value ->
                editingZfTermStart = false
                onZfTermStartChange(value.trim())
            },
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            icon = {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
            },
            title = { Text("清空本地缓存？") },
            text = { Text("会删除已保存的课表数据，下次打开需要联网重新同步。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        onClearCache()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 设置内容：单栏或两栏。
 *
 * 两种排法用的是同一批 [SettingsSection] 分组，只是外层容器不同，
 * 所以新增设置项只需要改一处。
 */
@Composable
private fun SettingsContent(
    settings: UserSettings,
    state: ScheduleUiState,
    cacheSizeBytes: Long,
    twoColumns: Boolean,
    onEditZfTermStart: () -> Unit,
    onZfLogin: () -> Unit,
    onZfLogout: () -> Unit,
    onEditTimetableUrl: () -> Unit,
    onEditBaseUrl: () -> Unit,
    onSourceKindChange: (TimetableSourceKind) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onHideUnscheduledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    updateChecking: Boolean,
    updateError: String?,
    latestVersion: String?,
    onCheckUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionSpacing = 24.dp

    if (twoColumns) {
        // Expanded：左右各一列分组，正文仍然不超过 840dp
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            item(key = "columns") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sectionSpacing),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    ) {
                        SourceSection(
                            settings = settings,
                            state = state,
                            onEditZfTermStart = onEditZfTermStart,
                            onZfLogin = onZfLogin,
                            onZfLogout = onZfLogout,
                            onEditTimetableUrl = onEditTimetableUrl,
                            onEditBaseUrl = onEditBaseUrl,
                            onSourceKindChange = onSourceKindChange,
                            onRefresh = onRefresh,
                        )
                        CacheSection(
                            cacheSizeBytes = cacheSizeBytes,
                            hasData = state.hasData,
                            onClear = onClearCache,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
                    ) {
                        AppearanceSection(
                            settings = settings,
                            onThemeModeChange = onThemeModeChange,
                            onDynamicColorChange = onDynamicColorChange,
                        )
                        DisplaySection(
                            settings = settings,
                            onHideUnscheduledChange = onHideUnscheduledChange,
                        )
                        VersionSection(
                            latestVersion = latestVersion,
                            checking = updateChecking,
                            error = updateError,
                            onCheck = onCheckUpdate,
                            onOpenRelease = onOpenRelease,
                        )
                    }
                }
            }
            item(key = "footer") { SettingsFooter() }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            item(key = "source") {
                SourceSection(
                    settings = settings,
                    state = state,
                            onEditZfTermStart = onEditZfTermStart,
                            onZfLogin = onZfLogin,
                            onZfLogout = onZfLogout,
                            onEditTimetableUrl = onEditTimetableUrl,
                            onEditBaseUrl = onEditBaseUrl,
                            onSourceKindChange = onSourceKindChange,
                            onRefresh = onRefresh,
                )
            }
            item(key = "cache") {
                CacheSection(
                    cacheSizeBytes = cacheSizeBytes,
                    hasData = state.hasData,
                    onClear = onClearCache,
                )
            }
            item(key = "appearance") {
                AppearanceSection(
                    settings = settings,
                    onThemeModeChange = onThemeModeChange,
                    onDynamicColorChange = onDynamicColorChange,
                )
            }
            item(key = "display") {
                DisplaySection(
                    settings = settings,
                    onHideUnscheduledChange = onHideUnscheduledChange,
                )
            }
            item(key = "version") {
                VersionSection(
                    latestVersion = latestVersion,
                    checking = updateChecking,
                    error = updateError,
                    onCheck = onCheckUpdate,
                    onOpenRelease = onOpenRelease,
                )
            }
            item(key = "footer") { SettingsFooter() }
        }
    }
}

@Composable
private fun SourceSection(
    settings: UserSettings,
    state: ScheduleUiState,
    onEditTimetableUrl: () -> Unit,
    onEditBaseUrl: () -> Unit,
    onEditZfTermStart: () -> Unit,
    onSourceKindChange: (TimetableSourceKind) -> Unit,
    onZfLogin: () -> Unit,
    onZfLogout: () -> Unit,
    onRefresh: () -> Unit,
) {
    SettingsSection(title = "课表来源") {
        // 两种数据源二选一：教务系统（登录）与分享链接（token）
        listOf(TimetableSourceKind.SHARE_LINK, TimetableSourceKind.ZF_LOGIN).forEach { kind ->
            val selected = settings.sourceKind == kind
            ListItem(
                modifier = Modifier.selectable(
                    selected = selected,
                    onClick = { onSourceKindChange(kind) },
                    role = Role.RadioButton,
                ),
                headlineContent = { Text(kind.displayName) },
                supportingContent = { Text(kind.description) },
                leadingContent = {
                    Icon(
                        if (kind == TimetableSourceKind.ZF_LOGIN) Icons.Outlined.School else Icons.Outlined.Link,
                        contentDescription = null,
                    )
                },
                trailingContent = { RadioButton(selected = selected, onClick = null) },
            )
            SettingDivider()
        }

        if (settings.sourceKind == TimetableSourceKind.ZF_LOGIN) {
            ListItem(
                headlineContent = { Text("教务系统登录") },
                supportingContent = {
                    Text(
                        if (state.zfLoggedIn) {
                            "已登录，同步时会带上会话；过期后会提示重新登录。"
                        } else {
                            "未登录。登录页需要输入账号并填写验证码（或用扫码登录）。"
                        },
                    )
                },
                leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                trailingContent = {
                    if (state.zfLoggedIn) {
                        TextButton(onClick = onZfLogout) { Text("退出") }
                    } else {
                        TextButton(onClick = onZfLogin) { Text("登录") }
                    }
                },
            )
            SettingDivider()
            ListItem(
                headlineContent = { Text("开学第一周周一") },
                supportingContent = {
                    Text(
                        settings.zfTermStartDate.ifBlank {
                            "未设置：按页面里的「当前周」反推；反推不准时在这里填，如 2026-08-24"
                        },
                    )
                },
                leadingContent = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onEditZfTermStart) { Text("设置") }
                },
            )
            SettingDivider()
        } else {
            ListItem(
                headlineContent = { Text("课表链接 / token") },
                supportingContent = {
                    Text(
                        text = settings.timetableUrl.ifBlank { "未设置，点右侧「修改」填写" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Link, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onEditTimetableUrl) { Text("修改") }
                },
            )
            SettingDivider()
            ListItem(
                headlineContent = { Text("服务地址") },
                supportingContent = {
                    Text(
                        text = settings.baseUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
                trailingContent = {
                    TextButton(onClick = onEditBaseUrl) { Text("修改") }
                },
            )
            SettingDivider()
        }

        ListItem(
            headlineContent = { Text("同步状态") },
            supportingContent = {
                Text(
                    text = syncSummary(state, settings),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
            trailingContent = {
                TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Text(if (state.isLoading) "同步中" else "刷新")
                }
            },
        )
    }
}

/** 数据源的展示名。 */
private val TimetableSourceKind.displayName: String
    get() = when (this) {
        TimetableSourceKind.ZF_LOGIN -> "教务系统（jwxt.sut.edu.cn）"
        TimetableSourceKind.SHARE_LINK -> "分享链接（token）"
    }

/** 数据源的一句话说明。 */
private val TimetableSourceKind.description: String
    get() = when (this) {
        TimetableSourceKind.ZF_LOGIN -> "备选：需登录（验证码手输），课表周次可能与实际有出入"
        TimetableSourceKind.SHARE_LINK -> "默认方式：粘贴课表分享链接或 token，无需登录"
    }

/** 「数据与缓存」分组。 */

@Composable
private fun CacheSection(
    cacheSizeBytes: Long,
    hasData: Boolean,
    onClear: () -> Unit,
) {
    SettingsSection(title = "数据与缓存") {
        ListItem(
            headlineContent = { Text("本地缓存") },
            supportingContent = {
                Text(
                    text = "占用 ${formatSize(cacheSizeBytes)}。" +
                        "同步成功后会自动保存，离线时直接打开也能看到课表。",
                )
            },
            leadingContent = {
                Icon(Icons.Outlined.Storage, contentDescription = null)
            },
            trailingContent = {
                TextButton(
                    onClick = onClear,
                    enabled = cacheSizeBytes > 0 || hasData,
                ) {
                    Text("清空")
                }
            },
        )
    }
}

/** 「外观」分组：主题模式 + 动态取色。 */
@Composable
private fun AppearanceSection(
    settings: UserSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "外观") {
        ThemeMode.entries.forEach { mode ->
            val selected = settings.themeMode == mode
            ListItem(
                modifier = Modifier.selectable(
                    selected = selected,
                    onClick = { onThemeModeChange(mode) },
                    role = Role.RadioButton,
                ),
                headlineContent = { Text(mode.displayName) },
                trailingContent = {
                    RadioButton(selected = selected, onClick = null)
                },
            )
        }
        SettingDivider()
        ListItem(
            modifier = Modifier.toggleable(
                value = settings.dynamicColor,
                onValueChange = onDynamicColorChange,
                role = Role.Switch,
            ),
            headlineContent = { Text("动态取色") },
            supportingContent = {
                Text("Android 12 及以上跟随系统壁纸配色")
            },
            leadingContent = {
                Icon(Icons.Outlined.Palette, contentDescription = null)
            },
            trailingContent = {
                Switch(checked = settings.dynamicColor, onCheckedChange = null)
            },
        )
    }
}

/** 「显示」分组。 */
@Composable
private fun DisplaySection(
    settings: UserSettings,
    onHideUnscheduledChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "显示") {
        ListItem(
            modifier = Modifier.toggleable(
                value = settings.hideUnscheduled,
                onValueChange = onHideUnscheduledChange,
                role = Role.Switch,
            ),
            headlineContent = { Text("隐藏未排课课程") },
            supportingContent = {
                Text("未排课指培养方案里有、但本学期没有安排时间的课程")
            },
            leadingContent = {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
            },
            trailingContent = {
                Switch(checked = settings.hideUnscheduled, onCheckedChange = null)
            },
        )
    }
}

/** 「版本与更新」分组。 */
@Composable
private fun VersionSection(
    latestVersion: String?,
    checking: Boolean,
    error: String?,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    SettingsSection(title = "版本") {
        UpdateRow(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = latestVersion,
            checking = checking,
            error = error,
            onCheck = onCheck,
            onOpenRelease = onOpenRelease,
        )
    }
}

/** 页脚版本号。 */
@Composable
private fun SettingsFooter() {
    Text(
        text = "Wakeup 课表 v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/** 设置分组：labelLarge 标题 + 若干 [ListItem] 行。 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        content()
    }
}

/** 设置行之间的分隔线：与列表内容左对齐。 */
@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 链接编辑对话框：标题 + 单行输入框 + 取消/确认。 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    placeholder: String,
    supportingText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by rememberSaveable(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(label) },
                placeholder = {
                    Text(
                        text = placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingText = { Text(supportingText) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft.trim()) },
                enabled = draft.isNotBlank(),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 同步状态摘要：上次同步、账号、学期、课程数量与缓存状态。 */
private fun syncSummary(state: ScheduleUiState, settings: UserSettings): String = buildString {
    // 同步来源：写明这份课表是从哪来的
    val label = when (settings.sourceKind) {
        TimetableSourceKind.ZF_LOGIN -> "教务系统"
        TimetableSourceKind.SHARE_LINK -> "分享链接"
    }
    val host = when (settings.sourceKind) {
        TimetableSourceKind.ZF_LOGIN -> settings.zfBaseUrl
        TimetableSourceKind.SHARE_LINK -> settings.baseUrl
    }.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    append("来源：$label（$host）")
    append("\n上次同步：${TimeUtils.relativeTime(state.lastSyncedAt)}")
    if (state.isFromCache) append(" · 使用本地缓存")
    state.userName?.let { append(" · 账号 $it") }
    state.timetable.term.name.takeIf { it.isNotBlank() }?.let { append(" · 学期 $it") }
    if (state.hasData) {
        append(" · 已排课 ${state.timetable.scheduled.size} 门")
        if (state.timetable.unscheduled.isNotEmpty()) {
            append(" · 未排课 ${state.timetable.unscheduled.size} 门")
        }
    }
    state.errorMessage?.let { append("\n错误：$it") }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "无"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
}

private val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

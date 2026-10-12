package com.wakeup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wakeup.BuildConfig
import com.wakeup.data.remote.WakeupApiFactory
import com.wakeup.data.repository.ThemeMode
import com.wakeup.data.repository.UserSettings
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.components.UpdateRow
import com.wakeup.util.TimeUtils

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    state: ScheduleUiState,
    cacheSizeBytes: Long,
    onTimetableUrlChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onHideUnscheduledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onOpenRelease: (String) -> Unit,
    updateChecking: Boolean = false,
    updateError: String? = null,
    latestVersion: String? = null,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var urlDraft by rememberSaveable(settings.timetableUrl) { mutableStateOf(settings.timetableUrl) }
    var baseUrlDraft by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var showBaseUrl by rememberSaveable { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }

    val urlChanged = urlDraft.trim() != settings.timetableUrl

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- 课表来源 ----
            SectionTitle("课表来源")

            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("课表链接") },
                placeholder = { Text(WakeupApiFactory.TIMETABLE_URL_EXAMPLE) },
                supportingText = {
                    Text("粘贴教务系统里的完整课表链接；也可以只填 token（如 100000）。")
                },
                singleLine = true,
                isError = urlDraft.isBlank(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onTimetableUrlChange(urlDraft) },
                    enabled = !state.isLoading && urlDraft.isNotBlank() && urlChanged,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isLoading) {
                        LoadingIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isLoading) "同步中" else "保存并同步")
                }
                OutlinedButton(
                    onClick = {
                        urlDraft = settings.timetableUrl
                        onRefresh()
                    },
                    enabled = !state.isLoading,
                ) {
                    Text("刷新")
                }
            }

            TextButton(onClick = { showBaseUrl = !showBaseUrl }) {
                Text(if (showBaseUrl) "收起服务地址" else "只填 token？设置服务地址")
            }

            if (showBaseUrl) {
                OutlinedTextField(
                    value = baseUrlDraft,
                    onValueChange = { baseUrlDraft = it },
                    label = { Text("服务地址（token 模式使用）") },
                    placeholder = { Text(WakeupApiFactory.DEFAULT_BASE_URL) },
                    supportingText = { Text("只填 token 时，会拼成「服务地址/user/kb/table/token/<token>」。") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                onBaseUrlChange(baseUrlDraft)
                            },
                            enabled = baseUrlDraft.trim() != settings.baseUrl,
                        ) { Text("保存") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- 数据与缓存 ----
            SectionTitle("数据与缓存")

            InfoCard(
                icon = Icons.Outlined.CloudSync,
                title = "同步状态",
                lines = buildList {
                    add("上次同步：${TimeUtils.relativeTime(state.lastSyncedAt)}")
                    state.userName?.let { add("账号：$it") }
                    state.timetable.term.name.takeIf { it.isNotBlank() }?.let { add("学期：$it") }
                    if (state.hasData) {
                        add(
                            "课程：${state.timetable.scheduled.size} 门已排课" +
                                if (state.timetable.unscheduled.isNotEmpty()) {
                                    "，${state.timetable.unscheduled.size} 门未排课"
                                } else {
                                    ""
                                },
                        )
                    }
                    state.errorMessage?.let { add("错误：$it") }
                },
                onRefresh = onRefresh,
                refreshing = state.isLoading,
            )

            InfoCard(
                icon = Icons.Outlined.Storage,
                title = "本地缓存",
                lines = buildList {
                    add("缓存大小：${formatSize(cacheSizeBytes)}")
                    add(
                        if (state.hasData && state.isFromCache) {
                            "当前正在使用缓存数据，联网后刷新即可更新。"
                        } else {
                            "同步成功后会自动保存，离线时直接打开也能看到课表。"
                        },
                    )
                },
                onRefresh = null,
                refreshing = false,
            )

            OutlinedButton(
                onClick = { confirmClearCache = true },
                enabled = cacheSizeBytes > 0 || state.hasData,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("清空本地缓存")
            }

            // ---- 外观 ----
            SectionTitle("外观")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DarkMode, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("主题模式", style = MaterialTheme.typography.bodyLarge)
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                        label = { Text(mode.displayName) },
                    )
                }
            }

            SettingsSwitch(
                icon = Icons.Outlined.Palette,
                title = "动态取色",
                subtitle = "Android 12 及以上跟随系统壁纸配色",
                checked = settings.dynamicColor,
                onCheckedChange = onDynamicColorChange,
            )

            // ---- 显示 ----
            SectionTitle("显示")

            SettingsSwitch(
                icon = Icons.Outlined.Visibility,
                title = "隐藏未排课课程",
                subtitle = "未排课指培养方案里有、但本学期没有安排时间的课程",
                checked = settings.hideUnscheduled,
                onCheckedChange = onHideUnscheduledChange,
            )

            // ---- 版本与更新 ----
            SectionTitle("版本")

            UpdateRow(
                currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = latestVersion,
                checking = updateChecking,
                error = updateError,
                onCheck = onCheckUpdate,
                onOpenRelease = onOpenRelease,
            )

            Text(
                text = "Wakeup 课表 v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清空本地缓存？") },
            text = { Text("会删除已保存的课表数据，下次打开需要联网重新同步。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        onClearCache()
                        urlDraft = settings.timetableUrl
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("取消") }
            },
        )
    }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    lines: List<String>,
    onRefresh: (() -> Unit)?,
    refreshing: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null) {
                    TextButton(onClick = onRefresh, enabled = !refreshing) { Text("刷新") }
                }
            }
            Spacer(Modifier.height(6.dp))
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

package com.wakeup.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wakeup.util.TimeUtils
import com.wakeup.wear.BuildConfig
import com.wakeup.wear.WearUiState

/**
 * 手表端设置。
 *
 * 只保留"在手表上做得动"的几件事：刷新、换周次、填 token、清缓存、看版本。
 * 课表链接在手表上没有键盘可敲，所以只让用户填数字 token（见 [TokenScreen]）。
 */
@Composable
fun SettingsScreen(
    state: WearUiState,
    onRefresh: () -> Unit,
    onOpenWeekPicker: () -> Unit,
    onOpenToken: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { padding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "header") {
                ListHeader {
                    Text("设置", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }

            item(key = "refresh") {
                ActionCard(
                    title = if (state.isLoading) "正在同步…" else "刷新课表",
                    subtitle = syncSubtitle(state),
                    enabled = !state.isLoading,
                    onClick = onRefresh,
                )
            }

            item(key = "week") {
                ActionCard(
                    title = "选择周次",
                    subtitle = "当前第${state.week}周 / 共${state.totalWeeks}周",
                    onClick = onOpenWeekPicker,
                )
            }

            item(key = "token") {
                ActionCard(
                    title = "课表 token",
                    subtitle = if (state.hasSource) "已配置，点击修改" else "未配置，点击填写",
                    onClick = onOpenToken,
                )
            }

            item(key = "clear") {
                ActionCard(
                    title = "清空本地缓存",
                    subtitle = "出错时可以清一次再刷新",
                    onClick = onClearCache,
                )
            }

            item(key = "version") {
                Text(
                    text = "Wakeup 手表版 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun syncSubtitle(state: WearUiState): String {
    val syncedAt = state.lastSyncedAt
    if (syncedAt <= 0L) return "尚未同步"
    return "上次同步：${TimeUtils.relativeTime(syncedAt)}"
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

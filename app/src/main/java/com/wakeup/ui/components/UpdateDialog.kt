package com.wakeup.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wakeup.BuildConfig
import com.wakeup.data.update.UpdateInfo

/**
 * 发现新版本时的提示。
 *
 * 只提供「去下载」（打开 GitHub Release 页面），不做应用内静默安装 ——
 * 后者需要 REQUEST_INSTALL_PACKAGES 权限，也会被系统的未知来源限制拦住。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String = BuildConfig.VERSION_NAME,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                contentDescription = null,
            )
        },
        title = { Text("发现新版本 ${info.versionLabel}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前版本 v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.notes?.let { notes ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Text(
                    text = "点击「去下载」打开发布页，下载 APK 后手动安装。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        // 优先用版本清单里的加速直链；没有清单时退回发布页
                        val target = info.downloadUrl.ifBlank { info.releaseUrl }
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    onDismiss()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("去下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}

/**
 * 设置页里的「检查更新」行。
 *
 * 与设置页其它行一致，使用 [ListItem]：标题 + 状态说明 + 尾部操作按钮。
 */
@Composable
fun UpdateRow(
    currentVersion: String,
    latestVersion: String?,
    checking: Boolean,
    error: String?,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
    releaseUrl: String = BuildConfig.RELEASES_PAGE_URL,
) {
    val hasUpdate = latestVersion != null

    ListItem(
        headlineContent = { Text("检查更新") },
        supportingContent = {
            Text(
                text = when {
                    checking -> "正在检查…"
                    hasUpdate -> "发现新版本 v$latestVersion"
                    error != null -> error
                    else -> "当前版本 v$currentVersion"
                },
                color = if (hasUpdate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 3,
            )
        },
        leadingContent = {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
        },
        trailingContent = {
            TextButton(
                onClick = {
                    if (hasUpdate) onOpenRelease(releaseUrl) else onCheck()
                },
                enabled = !checking,
            ) {
                Text(if (hasUpdate) "去下载" else "检查")
            }
        },
    )
}

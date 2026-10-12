package com.wakeup.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
                modifier = Modifier.size(24.dp),
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
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Text(
                    text = "点击「去下载」打开发布页，下载 APK 后手动安装。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    onDismiss()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("去下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        },
    )
}

/** 设置页里的「检查更新」行。 */
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.FileDownload,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("检查更新", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    checking -> "正在检查…"
                    latestVersion != null -> "发现新版本 v$latestVersion"
                    error != null -> error
                    else -> "当前版本 v$currentVersion"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (latestVersion != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (latestVersion != null) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = { if (latestVersion != null) onOpenRelease(releaseUrl) else onCheck() },
            enabled = !checking,
        ) {
            Text(if (latestVersion != null) "去下载" else "检查")
        }
    }
}

package com.wakeup.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import com.wakeup.data.remote.timetableUrlToToken
import com.wakeup.wear.WearUiState

/**
 * 填课表 token。
 *
 * 手表上没有输入框（也不该在表盘上敲链接），所以只做**数字键盘**：
 * 教务系统的课表链接形如 `.../user/kb/table/token/43499`，后半段就是纯数字，
 * 拼链接的部分交给 `tokenToTimetableUrl`，两端算出来的链接完全一致。
 */
@Composable
fun TokenScreen(
    state: WearUiState,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
) {
    val currentToken = remember(state.sourceUrl) { timetableUrlToToken(state.sourceUrl) }
    var input by remember(state.sourceUrl) { mutableStateOf("") }
    val listState = rememberScalingLazyListState()

    val preview = input.ifBlank { currentToken }

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
                    Text(
                        text = "课表 token",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                }
            }

            item(key = "preview") {
                Card(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Text(
                        text = if (preview.isBlank()) "还没有 token" else "当前：$preview",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 0-9：点一下追加一位，边点边能看到"当前"那行的变化
            items(count = KEYPAD.size, key = { index -> "key-${KEYPAD[index]}" }) { index ->
                val digit = KEYPAD[index]
                Card(
                    onClick = { input = (input + digit).take(MAX_TOKEN_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = digit,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item(key = "delete") {
                Card(
                    onClick = { input = input.dropLast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Text(
                        text = if (input.isEmpty()) "删除（还没输入）" else "删除一位",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item(key = "confirm") {
                Card(
                    onClick = {
                        val token = preview
                        if (token.isNotBlank()) onConfirm(token)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Text(
                        text = "保存并同步",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 手表上数字键盘的顺序（1-9 再 0）。 */
private val KEYPAD = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/** token 都是纯数字，长度给点余量。 */
private const val MAX_TOKEN_LENGTH = 24

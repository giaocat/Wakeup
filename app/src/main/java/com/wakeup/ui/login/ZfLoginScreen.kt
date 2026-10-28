package com.wakeup.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 给没有 viewport 的桌面版登录页补一个 viewport，并按手机宽度排版。 */
private const val VIEWPORT_SCRIPT: String =
    "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);}" +
        "m.setAttribute('content','width=device-width, initial-scale=1, minimum-scale=0.8, maximum-scale=4, user-scalable=yes');})()"

/**
 * 应用内登录教务系统（正方）。
 *
 * 为什么要在应用内登录：新教务系统的登录页带**图形验证码**和扫码登录，
 * 后端没法用账号密码静默登录。这里放一个 WebView 让用户正常登录一次，
 * 登录成功后把 Cookie 交给 ViewModel 保存，之后抓课表页就带这串 Cookie。
 *
 * 判定"登录成功"不能只看 URL：正方**未登录**时访问 `xsMain.jsp` 也返回 200 并停在登录页，
 * 而且匿名访问同样会下发 JSESSIONID。所以这里注入一段 JS 检查登录表单是否还在，
 * 不在表单了才算登录完成；同时保留右上角「完成」让用户手动触发。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZfLoginScreen(
    baseUrl: String,
    onLoggedIn: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val base = baseUrl.trim().trimEnd('/').ifEmpty { "https://jwxt.sut.edu.cn" }
    var errorText by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    /**
     * 取出登录后的 Cookie。
     *
     * **必须按 `/jsxsd/` 这个路径取**：教务系统对 `JSESSIONID` 按路径下发了两个
     * （`Path=/jsxsd` 是课表真正用的会话，`Path=/` 是另一个），
     * 只读根路径会拿到错的/空的会话，表现就是"登录后闪一下又要求登录"。
     * 合并时以 `/jsxsd` 的为准，根路径只补缺失的名字。
     */
    fun cookieHeader(): String {
        val cookies = CookieManager.getInstance()
        runCatching { cookies.flush() }
        val scoped = runCatching { cookies.getCookie("$base/jsxsd/") }.getOrNull().orEmpty()
        val root = runCatching { cookies.getCookie(base) }.getOrNull().orEmpty()
        val merged = LinkedHashMap<String, String>()
        listOf(scoped, root).forEach { raw ->
            raw.split(';').forEach { pair ->
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty() && !merged.containsKey(name)) {
                    merged[name] = value
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun finishWithCookie() {
        val cookie = cookieHeader()
        if (cookie.isNotBlank()) {
            android.util.Log.i("WakeupLogin", "会话长度=${cookie.length}，名字=${cookie.split(';').map { it.substringBefore('=').trim() }}")
            onLoggedIn(cookie)
        } else {
            errorText = "还没有读到登录状态：请先在下方页面完成登录（验证码或扫码），再点右上角「完成」"
        }
    }


    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (checking) "正在确认登录状态…" else "登录教务系统") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(onClick = { finishWithCookie() }) { Text("完成") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "登录一次即可：登录后 App 保存会话并自动同步课表；" +
                    "会话过期时这里会再次提示。登录页由学校提供，账号密码不会经过本应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            errorText?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 教务系统是桌面版页面、没有 viewport：允许缩放，并注入 viewport 让它按手机宽度排版
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        cookies.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                // 只在进入登录后的框架页时再确认状态。
                                // 不能一进 /jsxsd/ 就判断：登录页本身也在 /jsxsd/ 下，而且
                                // 直接打开框架页时服务器会返回 `{"flag":2,...}` 这种 JSON 报错。
                                if (url == null || !url.contains("/jsxsd/framework/")) return
                                view.evaluateJavascript(
                                    "(function(){" +
                                        "var t=(document.body&&document.body.innerText||'').trim();" +
                                        "if(t.indexOf('\"flag\"')===0||t.indexOf('跳转链接地址异常')>=0) return 'jump';" +
                                        "if(document.getElementById('userAccount')) return 'login';" +
                                        "return 'ok';" +
                                        "})()",
                                ) { result ->
                                    if (result == null) return@evaluateJavascript
                                    when {
                                        result.contains("ok") -> {
                                            checking = true
                                            // 留一点时间让后续跳转把 Cookie 写完
                                            view.postDelayed({
                                                checking = false
                                                finishWithCookie()
                                            }, 800)
                                        }
                                        // 服务器说"跳转链接地址异常"：说明这个入口不对，退回登录页重来
                                        result.contains("jump") -> {
                                            errorText = "已登录，但教务系统拒绝直接打开课表页；点右上角「完成」重试同步"
                                        }
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // 子资源失败不打扰用户，只把主文档失败显示出来
                                if (request.isForMainFrame) {
                                    errorText = "打不开登录页（${error.description}），请检查网络或教务系统地址"
                                }
                            }
                        }
                        // 从登录入口进：直接开 xsMain.jsp 会被系统判成「跳转链接地址异常」
                        loadUrl("$base/jsxsd/")
                    }
                },
                onRelease = { webView -> webView.destroy() },
            )
        }
    }
}

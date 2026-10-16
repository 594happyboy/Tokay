package com.happyboy.tokay.demo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.happyboy.tokay.demo.ui.theme.TokayTheme
import com.happyboy.tokay.engine.webview.HybridWebViewError
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.interceptor.RequestAction
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception
import com.happyboy.tokay.engine.webview.interceptor.RequestMatch
import com.happyboy.tokay.engine.webview.interceptor.RequestRule

/** 预览回调的观测日志 tag，只在演示 app 侧使用。 */
private const val LOAD_ERROR_TAG = "LoadErrorCb"

private const val DEFAULT_URL = "https://www.baidu.com"

class HybridWebViewApiTestActivity : ComponentActivity() {

    companion object {
        /**
         * 初始加载 URL（真机验收钩子）：`DevHomeActivity` 的 `preview_url` 会转发到这里。
         */
        const val EXTRA_PREVIEW_URL = "preview_url"
    }

    private var hybridWebView: HybridWebView? = null
    private var initialUrl: String = DEFAULT_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = intent?.getStringExtra(EXTRA_PREVIEW_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        enableEdgeToEdge()
        setContent {
            TokayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HybridWebViewApiTestScreen(
                        initialUrl = initialUrl,
                        onWebViewCreated = { hybridWebView = it }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hybridWebView?.onResume()
    }

    override fun onPause() {
        hybridWebView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        hybridWebView?.destroy()
        hybridWebView = null
        super.onDestroy()
    }
}

private data class ApiAction(
    val label: String,
    val handler: (HybridWebView, (String) -> Unit) -> Unit
)

@Composable
private fun HybridWebViewApiTestScreen(
    initialUrl: String,
    onWebViewCreated: (HybridWebView) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<HybridWebView?>(null) }
    val lastMessage: MutableState<String> = remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f),
            factory = { ctx ->
                HybridWebView(ctx).also { hvw ->
                    webView = hvw
                    onWebViewCreated(hvw)

                    hvw.setLoadListener(object : HybridWebView.LoadListener {
                        override fun onPageStarted(url: String?) {
                            showToast(context, "onPageStarted: $url")
                        }

                        override fun onPageFinished(url: String?) {
                            showToast(context, "onPageFinished: $url")
                        }

                        override fun onProgressChanged(progress: Int) {
                            lastMessage.value = "progress: $progress"
                        }

                        /** 加载失败的唯一出口，带 phase 与非空 code。 */
                        override fun onLoadError(error: HybridWebViewError) {
                            Log.w(
                                LOAD_ERROR_TAG,
                                "onLoadError phase=${error.phase} code=${error.code} message=${error.message}"
                            )
                        }
                    })

                    hvw.loadUrl(initialUrl)
                }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "最近一次结果：${lastMessage.value}",
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        val actions = rememberApiActions()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(actions) { action ->
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = {
                        val hvw = webView
                        if (hvw == null) {
                            showToast(context, "HybridWebView 未初始化")
                        } else {
                            action.handler(hvw) { msg ->
                                lastMessage.value = msg
                                showToast(context, msg)
                            }
                        }
                    }
                ) {
                    Text(text = action.label)
                }
            }
        }
    }
}

@Composable
private fun rememberApiActions(): List<ApiAction> {
    return remember {
        listOf(
            // 导航与加载
            ApiAction("加载示例页面 https://www.baidu.com") { hvw, notify ->
                hvw.loadUrl("https://www.baidu.com")
                notify("调用 loadUrl(example.com)")
            },
            ApiAction("加载 Gecko 官方页面 https://www.mozilla.org") { hvw, notify ->
                hvw.loadUrl("https://www.mozilla.org")
                notify("调用 loadUrl(mozilla.org)")
            },
            ApiAction("reload 重新加载") { hvw, notify ->
                hvw.reload()
                notify("调用 reload()")
            },
            ApiAction("goBack 后退") { hvw, notify ->
                hvw.goBack()
                notify("调用 goBack()")
            },
            ApiAction("goForward 前进") { hvw, notify ->
                hvw.goForward()
                notify("调用 goForward()")
            },
            ApiAction("canGoBack / canGoForward 查询") { hvw, notify ->
                val canBack = hvw.canGoBack()
                val canForward = hvw.canGoForward()
                notify("canGoBack=$canBack, canGoForward=$canForward")
            },
            ApiAction("stopLoading 停止加载") { hvw, notify ->
                hvw.stopLoading()
                notify("调用 stopLoading()")
            },

            // 状态查询
            ApiAction("getUrl / getTitle / getProgress / isLoading") { hvw, notify ->
                val url = hvw.getUrl()
                val title = hvw.getTitle()
                val progress = hvw.getProgress()
                val loading = hvw.isLoading()
                notify("url=$url, title=$title, progress=$progress, isLoading=$loading")
            },

            // JS & UA
            ApiAction("setUserAgent 自定义 UA") { hvw, notify ->
                hvw.setUserAgent("HybridWebView-Test-UA")
                notify("已设置自定义 UA=HybridWebView-Test-UA")
            },
            ApiAction("evaluateJavascript 读取 document.title") { hvw, notify ->
                hvw.evaluateJavascript("document.title") { result ->
                    notify("evaluateJavascript 返回: $result")
                }
            },
            // 对象/数字/布尔结果走的是另一条序列化分支（JS 侧包成 json 文本），单独点一次才看得见
            ApiAction("evaluateJavascript 读取对象/数字/布尔结果") { hvw, notify ->
                hvw.evaluateJavascript("({title: document.title, n: 1 + 1, ok: true})") { result ->
                    notify("evaluateJavascript 返回: $result")
                }
            },
            ApiAction("add/remove JavascriptInterface") { hvw, notify ->
                val name = "TestBridge"
                val jsInterface = object {
                    @android.webkit.JavascriptInterface
                    fun ping(msg: String) {
                        // 仅用于验证接口能被成功注册/移除
                    }
                }
                hvw.addJavascriptInterface(name, jsInterface)

                // 页面侧的 window.<name> 是收到 createInterface 消息之后才建出来的，与本次 eval 不同步，
                // 所以允许重试几次再判定：这里验的是「接口在页面里真的可用」，而不是「消息发出去了」。
                var attempt = 0
                fun verifyBridge() {
                    hvw.evaluateJavascript(
                        "typeof window.$name?.ping === 'function'" +
                            " ? (window.$name.ping('hi'), 'bridge-ok') : 'bridge-missing'"
                    ) { result ->
                        if (result == "\"bridge-missing\"" && attempt++ < 5) {
                            verifyBridge()
                        } else {
                            notify("JS 接口 $name → $result，随后移除")
                            // 上面那次 ping 是**异步**回到原生的（页面 → 插件 → 原生），立刻摘接口会让它在
                            // 原生侧报「接口不存在」，日志看起来像失败。等一拍再摘，完整往返都能看到。
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                { hvw.removeJavascriptInterface(name) },
                                1500
                            )
                        }
                    }
                }
                verifyBridge()
            },

            // 请求拦截：统一规则模型（正则）
            ApiAction("启用拦截：阻止所有 .png 资源") { hvw, notify ->
                val interception = RequestInterception(
                    enabled = true,
                    rules = listOf(
                        RequestRule(
                            id = "block_png",
                            priority = 0,
                            match = RequestMatch(urlRegex = "(?i)\\\\.png(\\\\?.*)?$"),
                            action = RequestAction.Block
                        )
                    )
                )
                hvw.setRequestInterception(interception)
                notify("已启用拦截：阻止所有 .png 资源")
            },
            ApiAction("loadData：直接加载 HTML 字符串") { hvw, notify ->
                hvw.loadData(
                    """
                        <!doctype html>
                        <html>
                        <head><meta charset="utf-8"><title>loadData</title></head>
                        <body style="font-family: sans-serif; padding: 16px;">
                          <h2>loadData 示例</h2>
                          <p>这段内容不依赖 URL。</p>
                        </body>
                        </html>
                    """.trimIndent(),
                    mimeType = "text/html",
                    encoding = "utf-8"
                )
                notify("已调用 loadData 加载 HTML")
            },
            ApiAction("关闭拦截（清空规则）") { hvw, notify ->
                hvw.setRequestInterception(null)
                notify("已关闭拦截")
            },

            // 生命周期 & 资源
            ApiAction("clearCache 清理缓存（含磁盘）") { hvw, notify ->
                hvw.clearCache(includeDiskFiles = true)
                notify("已触发 clearCache(includeDiskFiles=true)")
            },

            // 统计
            ApiAction("查询当前 WebView 类型 & 适配器状态") { hvw, notify ->
                val type = hvw.getCurrentWebViewType()
                val stats = hvw.getAdapterStats()
                notify("type=$type, stats=$stats")
            }
        )
    }
}

private fun showToast(context: android.content.Context, message: String?) {
    if (message.isNullOrEmpty()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

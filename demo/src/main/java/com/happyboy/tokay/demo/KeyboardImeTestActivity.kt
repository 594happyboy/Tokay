package com.happyboy.tokay.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.happyboy.tokay.demo.ui.theme.TokayTheme
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.HybridWebViewError

private const val TEST_URL = "hybrid://asset/test/keyboard_ime_test.html"

/**
 * 软键盘适配测试页：整屏 WebView + 顶部一块控制台。
 *
 * 窗口刻意做成**边到边 + `adjustNothing`**（`enableEdgeToEdge` + manifest 里那条
 * `windowSoftInputMode`）——这是系统帮不上忙的组合，也是 `setImeResizeEnabled` 唯一能救场的场合：
 * 打开「键盘避让」时库把键盘高度缩在 HybridWebView 自己身上，引擎随之把输入框滚进来；
 * 关掉它就能看到键盘把底部输入框吃掉（对照组）。
 *
 * 页面内的测试网页属于浏览器内容，不在本页样式范围内。
 */
class KeyboardImeTestActivity : ComponentActivity() {

    private var hybridWebView: HybridWebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：系统没有可裁剪的窗口空间，SOFT_INPUT_ADJUST_RESIZE 在这里无效。
        enableEdgeToEdge()

        setContent {
            TokayTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    KeyboardImeTestScreen(
                        onWebViewCreated = { hybridWebView = it },
                        onBack = { finish() }
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

@Composable
private fun KeyboardImeTestScreen(
    onWebViewCreated: (HybridWebView) -> Unit,
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<HybridWebView?>(null) }
    var imeResizeEnabled by remember { mutableStateOf(true) }
    var useGecko by remember { mutableStateOf(true) }
    var deckExpanded by remember { mutableStateOf(true) }
    var engineSummary by remember { mutableStateOf("-") }
    var lastResult by remember { mutableStateOf("待命") }

    fun refreshEngineSummary() {
        val hvw = webView ?: return
        engineSummary = "${hvw.getCurrentWebViewType()}（Gecko可用=${hvw.getAdapterStats().geckoAvailable}）"
    }

    LaunchedEffect(webView) {
        val hvw = webView ?: return@LaunchedEffect
        hvw.setImeResizeEnabled(imeResizeEnabled)
        hvw.loadUrl(TEST_URL)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ControlDeck(
            expanded = deckExpanded,
            onToggleExpanded = { deckExpanded = !deckExpanded },
            imeResizeEnabled = imeResizeEnabled,
            useGecko = useGecko,
            engineSummary = engineSummary,
            lastResult = lastResult,
            onBack = onBack,
            onToggleImeResize = {
                val next = !imeResizeEnabled
                imeResizeEnabled = next
                webView?.setImeResizeEnabled(next)
                lastResult = if (next) {
                    "已开启键盘避让"
                } else {
                    "已关闭键盘避让（对照：底部输入框会被键盘挡住）"
                }
            },
            onReload = {
                webView?.loadUrl(TEST_URL)
                lastResult = "正在重载测试页"
            },
            onSwitchAdapter = {
                val hvw = webView ?: return@ControlDeck
                val toGecko = !useGecko
                val switched = if (toGecko) hvw.switchToGeckoView() else hvw.switchToSystemWebView()
                if (switched) {
                    useGecko = toGecko
                    hvw.setImeResizeEnabled(imeResizeEnabled)
                    hvw.loadUrl(TEST_URL)
                    refreshEngineSummary()
                    lastResult = if (toGecko) "已切到 GeckoView 并重载" else "已切到系统 WebView 并重载"
                } else {
                    lastResult = if (toGecko) "切到 GeckoView 失败（内核不可用？）" else "切到系统 WebView 失败"
                }
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                HybridWebView(ctx).also { hvw ->
                    webView = hvw
                    onWebViewCreated(hvw)
                    hvw.setLoadListener(object : HybridWebView.LoadListener {
                        override fun onPageStarted(url: String?) {
                            refreshEngineSummary()
                            lastResult = "开始加载：${url ?: "-"}"
                        }

                        override fun onPageFinished(url: String?) {
                            refreshEngineSummary()
                            lastResult = "加载完成：${url ?: "-"}"
                        }

                        override fun onLoadError(error: HybridWebViewError) {
                            refreshEngineSummary()
                            lastResult = "加载失败：${error.message ?: error.code}（phase=${error.phase}）"
                        }
                    })
                }
            }
        )

    }
}

@Composable
private fun ControlDeck(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    imeResizeEnabled: Boolean,
    useGecko: Boolean,
    engineSummary: String,
    lastResult: String,
    onBack: () -> Unit,
    onToggleImeResize: () -> Unit,
    onReload: () -> Unit,
    onSwitchAdapter: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "软键盘适配测试",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleExpanded) { Text(if (expanded) "收起" else "展开") }
            }

            if (!expanded) return@Column

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (imeResizeEnabled) "键盘避让：开" else "键盘避让：关（对照）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (imeResizeEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    KeyValueRow("窗口", "边到边 + adjustNothing（系统帮不上忙）")
                    KeyValueRow("当前内核", engineSummary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onToggleImeResize) {
                    Text(if (imeResizeEnabled) "键盘避让：开" else "键盘避让：关")
                }
                Button(modifier = Modifier.weight(1f), onClick = onReload) {
                    Text("重载测试页")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onSwitchAdapter) {
                    Text(if (useGecko) "切系统 WebView" else "切 GeckoView")
                }
            }

            // 固定两行：文本再长也只截断，不会把上面的按钮顶走。
            Text(
                text = "最近结果：$lastResult",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

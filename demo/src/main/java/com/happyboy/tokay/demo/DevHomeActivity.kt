package com.happyboy.tokay.demo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.demo.ui.theme.TokayTheme
import com.happyboy.tokay.scheduler.PrewarmStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DevHomeActivity"

/**
 * 本 app 唯一的 launcher（也是唯一 `exported="true"` 的 Activity）：调试内核与视图层用的最小入口。
 *
 * 只放三块：内核状态、预热、两个测试页入口。安装进度不在页面上展开，需要看细节时看
 * logcat（`GeckoSdk` 在每次状态变化时都打日志）。内核坏了不需要人工干预：启动时的健康检查会把
 * 不完整的安装清掉并重新安装（重装内核的按钮因此删掉了——健康时它只是短路，坏时也修不了）。
 */
class DevHomeActivity : ComponentActivity() {

    companion object {
        /**
         * 真机验收钩子：`-e preview_url "<url>"` 直接打开任意 URL（含故意失败的地址）。
         *
         * ```
         * adb shell am start -n com.happyboy.tokay.demo/.DevHomeActivity \
         *     -e preview_url "https://www.mozilla.org"
         * ```
         *
         * 转发完**立刻 finish**，不留返回栈；SDK 侧不认识这个 extra。
         */
        const val EXTRA_PREVIEW_URL = "preview_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.getStringExtra(EXTRA_PREVIEW_URL)?.takeIf { it.isNotBlank() }?.let { url ->
            Log.i(TAG, "preview_url 钩子：转发到 HybridWebView API 测试页 $url")
            startActivity(
                Intent(this, HybridWebViewApiTestActivity::class.java)
                    .putExtra(HybridWebViewApiTestActivity.EXTRA_PREVIEW_URL, url)
            )
            finish()
            return
        }

        setContent {
            TokayTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DevHomeScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevHomeScreen() {
    val context = LocalContext.current

    var currentVersion by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(false) }
    var prewarmStatus by remember { mutableStateOf(PrewarmStatus()) }

    // 轮询而非只读一次：安装（含启动健康检查触发的重装）是异步的，就绪位要自己变过来。
    val refresh: () -> Unit = {
        installed = GeckoSdk.isInstalled
        currentVersion = runCatching { GeckoSdk.currentVersion }.getOrNull()
        prewarmStatus = runCatching { GeckoSdk.prewarmStatus }.getOrDefault(PrewarmStatus())
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(2000)
            refresh()
        }
    }

    val runAction: (String, () -> String) -> Unit = { label, action ->
        val message = runCatching(action).getOrElse { "«$label» 失败：${it.message}" }
        Log.i(TAG, "$label -> $message")
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tokay 开发者工具") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle("内核状态")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValueRow("内核来源", if (BuildConfig.PACKAGED_KERNEL) "随包（APK assets）" else "远程 OTA")
                    KeyValueRow("当前内核版本", currentVersion ?: "无")
                    KeyValueRow("本机 ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
                    KeyValueRow("是否就绪", if (installed) "就绪" else "未就绪")
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { refresh() }) {
                        Text("刷新")
                    }
                }
            }

            SectionTitle("预热")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = prewarmLine(prewarmStatus),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        runAction("触发预热") {
                            GeckoSdk.triggerPrewarm()
                            "已请求预热（异步，状态随轮询刷新）"
                        }
                    }
                ) {
                    Text("触发预热")
                }
            }

            SectionTitle("测试入口")
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { context.startActivity(Intent(context, HybridWebViewApiTestActivity::class.java)) }
            ) {
                Text("HybridWebView API 测试")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { context.startActivity(Intent(context, KeyboardImeTestActivity::class.java)) }
            ) {
                Text("软键盘适配测试")
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * 预热状态压成一行：Gecko 的三个部件（Runtime / Session / View）与 WebView 各自报，不合并成一句
 * "预热好了/还没"。
 *
 * 保守预热只热 Runtime，合并判断会把"Runtime 已热"读成"未预热"；分开报也让「触发预热」点下去的变化看得见。
 * 部件名沿用 Gecko 自己的术语，不翻译——翻译后反而对不上 `PrewarmStatus` 的字段与日志。
 */
private fun prewarmLine(status: PrewarmStatus): String {
    fun mark(ready: Boolean) = if (ready) "✓" else "✗"
    val gecko = "Gecko Runtime${mark(status.geckoRuntimeReady)}" +
        " Session${mark(status.geckoSessionReady)}" +
        " View${mark(status.geckoViewReady)}"
    val webView = if (status.webViewReady) "WebView 就绪" else "WebView 未就绪"
    val last = if (status.lastPrewarmTime > 0L) " · 上次 ${formatClock(status.lastPrewarmTime)}" else ""
    return "$gecko · $webView$last"
}

private fun formatClock(timeMillis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}

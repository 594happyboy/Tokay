package com.happyboy.tokay.engine.webview.adapters

import android.content.Context
import android.util.Log
import android.view.View
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.infrastructure.logging.SdkLogger
import com.happyboy.tokay.engine.gecko.delegates.GeckoDelegates
import com.happyboy.tokay.engine.gecko.runtime.GeckoRuntimeProvider
import com.happyboy.tokay.engine.gecko.runtime.GeckoSessionFactory
import com.happyboy.tokay.engine.server.LocalFileServer
import com.happyboy.tokay.engine.webview.HybridWebViewError
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.interceptor.DataPolicy
import com.happyboy.tokay.engine.webview.interceptor.InterceptionUrlMapper
import com.happyboy.tokay.engine.webview.interceptor.InterceptionLimits
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception
import com.happyboy.tokay.engine.webview.interceptor.RequestInterceptionEnv
import com.happyboy.tokay.engine.webview.interceptor.ServeBytesEntry
import com.happyboy.tokay.scheduler.PrewarmManager
import com.happyboy.tokay.engine.webview.bridge.HybridBridge
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import java.nio.charset.Charset

class GeckoWebViewAdapter(
    context: Context,
    hybridWebView: HybridWebView
) : AbstractWebViewAdapter(context, hybridWebView) {

    companion object {
        private const val LOAD_DATA_TTL_MILLIS: Long = 10 * 60 * 1000L

        private const val MIME_TYPE_TEXT_HTML = "text/html"
        private const val HTTP_HEADER_CACHE_CONTROL = "Cache-Control"
        private const val HTTP_HEADER_VALUE_NO_STORE = "no-store"
    }

    private var geckoView: GeckoView? = null
    private var session: GeckoSession? = null

    private var hybridBridge: HybridBridge? = null

    private var canGoBack: Boolean = false
    private var canGoForward: Boolean = false

    /** 本地服务持有令牌：构造期 acquire、doDestroy 时 release，只有最后一个持有者释放才停服。 */
    private var serverRefToken: Long? = null

    /**
     * 内核真实导航状态（已映射回 Hybrid URL 语义）。
     */
    private var currentUrl: String? = null
    private var currentTitle: String? = null

    private var requestInterception: RequestInterception? = null

    init {
        Log.d(LOG_TAG, "GeckoWebViewAdapter 初始化开始")
        try {
            // 必须先 acquire 再建 session/view：此后所有 URL 构造只读端口，不隐式启动服务；
            // 失败时由下面的 catch 走 doDestroy → release。
            serverRefToken = LocalFileServer.acquire(context)
            setupGeckoSession()
            Log.d(LOG_TAG, "GeckoWebViewAdapter 初始化完成")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "GeckoWebViewAdapter 初始化失败", e)
            try {
                doDestroy()
            } catch (cleanupException: Exception) {
                Log.e(LOG_TAG, "清理资源时发生错误", cleanupException)
            }
            throw e
        }
    }

    private fun setupGeckoSession() {
        val geckoSession = PrewarmManager.getGeckoSession() ?: createSession()
        val view = PrewarmManager.getGeckoView() ?: GeckoView(context)

        geckoView = view
        session = geckoSession

        view.setSession(geckoSession)
        setupBridgeManager(geckoSession)
    }
    
    private fun createSession(): GeckoSession {
        val runtime = GeckoRuntimeProvider.getOrCreate(context)
        return GeckoSessionFactory.create(runtime).apply {
            navigationDelegate = GeckoDelegates.Companion.navigation(
                onCanGoBack = { canGoBack = it },
                onCanGoForward = { canGoForward = it },
                onLocationChange = { url ->
                    currentUrl = if (url == null) {
                        null
                    } else {
                        InterceptionUrlMapper.localDomainRawUrlToHybridUrl(
                            context,
                            getLocalDomain(),
                            url
                        )
                    }
                },
                onLoadError = { error -> dispatchLoadError(error) }
            )
            contentDelegate = GeckoDelegates.Companion.content { title -> currentTitle = title }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    val startedUrl = InterceptionUrlMapper.localDomainRawUrlToHybridUrl(
                        context,
                        getLocalDomain(),
                        url
                    )
                    getLoadListener().forEach { it.onPageStarted(startedUrl) }
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    getLoadListener().forEach { it.onProgressChanged(progress) }
                }
            }
        }
    }

    /** 派发主文档加载错误：有 View 时切主线程，已 destroy 时直调；**不去重**，宿主需要完整序列。 */
    private fun dispatchLoadError(error: HybridWebViewError) {
        Log.w(LOG_TAG, "预览错误: ${error.describe()}")
        val view = geckoView
        if (view != null) {
            view.post { getLoadListener().forEach { it.onLoadError(error) } }
        } else {
            getLoadListener().forEach { it.onLoadError(error) }
        }
    }

    private fun setupBridgeManager(geckoSession: GeckoSession) {
        Log.d(LOG_TAG, "开始设置混合桥接系统")

        val bridge = HybridBridge(geckoSession, context)
        hybridBridge = bridge

        bridge.setOnReadyListener {
            Log.d(LOG_TAG, "混合桥接系统就绪")

            pushRequestRulesToExtensionIfReady()

            getLoadListener().forEach { it.onPageFinished(getUrl()) }
        }

        bridge.initialize()
    }

    override fun loadUri(uri: String) {
        hybridBridge?.reset()
                // 文档 URI 是敏感数据，必须受日志开关管辖（裸 Log.d 在 release 下照样输出）。
        SdkLogger.d("GeckoWebView", "开始加载新URL，已重置桥接系统状态: $uri")

        session?.loadUri(resolveToLocalUrl(uri))
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String?
    ) {
        val limits = requestInterception?.limits ?: InterceptionLimits()

        val effectiveData = if (
            baseUrl != null &&
            mimeType.startsWith(MIME_TYPE_TEXT_HTML, ignoreCase = true) &&
            !data.contains("<base", ignoreCase = true)
        ) {
            injectBaseHrefIntoHtml(data, baseUrl)
        } else {
            data
        }

        val charset = runCatching { Charset.forName(encoding) }.getOrNull() ?: Charsets.UTF_8

        val entry = ServeBytesEntry(
            bytes = effectiveData.toByteArray(charset),
            mimeType = mimeType,
            encoding = encoding,
            headers = mapOf(HTTP_HEADER_CACHE_CONTROL to HTTP_HEADER_VALUE_NO_STORE)
        )

        val key = LocalFileServer.putServeBytes(entry, DataPolicy.Ttl(LOAD_DATA_TTL_MILLIS), limits)
            ?: run {
                Log.w(LOG_TAG, "loadDataWithBaseURL: putServeBytes 失败，可能触发大小/总量限制")
                return
            }

        val url = LocalFileServer.buildDynamicUrl(context, key)
        loadUri(url)
    }

    private fun injectBaseHrefIntoHtml(html: String, baseUrl: String): String {
        val baseTag = "<base href=\"${baseUrl}\">"
        val headIndex = html.indexOf("<head", ignoreCase = true)
        if (headIndex >= 0) {
            val headClose = html.indexOf(">", startIndex = headIndex)
            if (headClose >= 0) {
                return html.substring(0, headClose + 1) + baseTag + html.substring(headClose + 1)
            }
        }
        return "<head>$baseTag</head>$html"
    }

    override fun evaluateJavascript(script: String, callback: ((Any?) -> Unit)?) {
        hybridBridge?.evaluateJavaScript(script, callback) ?: run {
            Log.w(LOG_TAG, "桥接系统未初始化，无法执行JavaScript")
            callback?.invoke(null)
        }
    }

    override fun addJavascriptInterface(name: String, obj: Any) {
        hybridBridge?.addJavaScriptInterface(name, obj) ?: run {
            Log.w(LOG_TAG, "桥接系统未初始化，无法添加JavaScript接口: $name")
        }
    }

    override fun removeJavascriptInterface(name: String) {
        hybridBridge?.removeJavaScriptInterface(name) ?: run {
            Log.w(LOG_TAG, "桥接系统未初始化，无法移除JavaScript接口: $name")
        }
    }

    /** 只读端口，不启动服务；未持有就调用会抛带说明的 IllegalStateException，而不是静默可用的 URL。 */
    override fun getLocalDomain(): String {
        return LocalFileServer.buildBaseUrl(context)
    }

    /** 归还本地服务持有权；令牌置空保证重复 destroy 时 release 是幂等 no-op。 */
    private fun stopServer() {
        val token = serverRefToken ?: return
        serverRefToken = null
        LocalFileServer.release(token)
    }

    override fun getView(): View = geckoView ?: throw IllegalStateException("GeckoView未初始化")

    override fun reload() {
        currentUrl?.let { loadUri(it) }
    }

    override fun doDestroy() {
        stopServer()
        hybridBridge?.destroy()
        hybridBridge = null
        session?.close()
        session = null
        Log.d(LOG_TAG, "GeckoWebViewAdapter已销毁")
    }

    override fun goBack() {
        if (canGoBack) session?.goBack()
    }

    override fun goForward() {
        if (canGoForward) session?.goForward()
    }

    override fun canGoBack(): Boolean = canGoBack
    override fun canGoForward(): Boolean = canGoForward
    override fun stopLoading() {
        session?.stop()
    }

    override fun getUrl(): String? = currentUrl
    override fun getTitle(): String? = currentTitle
    override fun setUserAgent(userAgent: String) {
        session?.settings?.userAgentOverride = userAgent
    }

    override fun onResume() {
        session?.setActive(true)
    }

    override fun onPause() {
        session?.setActive(false)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        try {
            val runtime = GeckoRuntimeProvider.getOrCreate(context)
            val storageController = runtime.storageController

            // Gecko 通路当前不区分内存/磁盘缓存，这里统一清理所有网络相关缓存。
            val flags = StorageController.ClearFlags.ALL_CACHES

            Log.d(
                LOG_TAG,
                "开始清理 Gecko 缓存: includeDiskFiles=$includeDiskFiles, flags=$flags"
            )

            storageController.clearData(flags)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "clearCache 调用 GeckoRuntime 失败", e)
        }
    }

    override fun updateRequestInterception(interception: RequestInterception?) {
        requestInterception = interception
        Log.d(
            LOG_TAG,
            "更新 Gecko 请求拦截规则: enabled=${interception?.enabled}, rulesSize=${interception?.rules?.size ?: 0}"
        )

        pushRequestRulesToExtensionIfReady()
    }

    private fun pushRequestRulesToExtensionIfReady() {
        val bridge = hybridBridge ?: return
        if (!bridge.isReady()) return

        val interception = requestInterception ?: RequestInterception(enabled = false)
        val localDomain = getLocalDomain()
        val env = RequestInterceptionEnv(
            localDomain = localDomain,
            filesDirName = context.filesDir.name
        )

        Log.d(
            LOG_TAG,
            "pushRequestRulesToExtensionIfReady: enabled=${interception.enabled}, rules=${interception.rules.size}, env=$env"
        )

        bridge.updateRequestInterceptionRules(interception, env)
    }
}

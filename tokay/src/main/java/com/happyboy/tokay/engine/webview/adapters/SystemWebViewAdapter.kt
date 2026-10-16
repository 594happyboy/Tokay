package com.happyboy.tokay.engine.webview.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.engine.webview.HybridWebViewErrorMapper
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.interceptor.InterceptionUrlMapper
import com.happyboy.tokay.engine.server.LocalFileServer
import com.happyboy.tokay.engine.server.MimeTypeHelper
import com.happyboy.tokay.engine.webview.interceptor.RequestAction
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception
import com.happyboy.tokay.engine.webview.interceptor.RequestInterceptionResolver
import com.happyboy.tokay.infrastructure.config.SdkConstants
import com.happyboy.tokay.infrastructure.logging.SdkLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

class SystemWebViewAdapter(
    context: Context,
    hybridWebView: HybridWebView
) : AbstractWebViewAdapter(context, hybridWebView) {

    // WebViewAssetLoader 把 SDK 自有路径翻译成 https 源（appassets.androidplatform.net）。
    //
    // 只暴露 /assets/ 与 /files/：`hybrid://file/...` 与 `file://<filesDir>/...` 都会被
    // AbstractWebViewAdapter.resolveToLocalUrl 翻译成本域的 /files/...，整项删掉等于让接入方的合法
    // 文件加载全部落空；SDK 自身的状态目录由 SdkInternalPathBlockingHandler 挡在页面之外。
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler(
                "/${context.filesDir.name}/",
                SdkInternalPathBlockingHandler(
                    WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir)
                )
            )
            .build()
    }

    private var compiledInterception: RequestInterceptionResolver.CompiledInterception? = null

    /**
     * 本地服务持有令牌：主路径走 WebViewAssetLoader，只有 `/dyn` 字节登记依赖本地服务；
     * 获取失败不阻断适配器构造。
     */
    private var serverRefToken: Long? = null

    init {
        serverRefToken = runCatching { LocalFileServer.acquire(context) }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    // file:// 源开关在 API 30 才废弃（那之后默认就是 false）；minSdk 26 必须显式关闭才在 26–29 生效。
    @Suppress("DEPRECATION")
    private val webView: WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 刻意保留 true：不落在 filesDir 下的 file:// URL 会原样交给 WebView（接入方的本地文件预览），
            // 关掉会静默破坏该能力，而它并不放大攻击面——真正危险的是下面两个「file:// 源」开关。
            allowFileAccess = true
            // 刻意保留 true：content:// 直读是接入方的场景，与 file:// 源隔离策略无关。
            allowContentAccess = true
            // 只放行「不执行脚本」的被动混合内容（图片/媒体等），主动混合内容（XHR/iframe/脚本）一律拦截。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 只对「以 file:// 为源」的页面生效，而 SDK 自有页面从不以 file:// 为源
            // （resolveToLocalUrl 把 hybrid:// 与 file://android_asset 都翻译成 http(s)），
            // 关掉它们只堵住「file:// 页面横向读取本地文件」，不影响自有功能。
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }

        webViewClient = createWebViewClient()

        webChromeClient = createWebChromeClient()
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                val hybridUrl = url?.let {
                    InterceptionUrlMapper.systemRawUrlToHybridUrl(context, it)
                }
                getLoadListener().forEach {
                    it.onPageStarted(hybridUrl)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val hybridUrl = url?.let {
                    InterceptionUrlMapper.systemRawUrlToHybridUrl(context, it)
                }
                getLoadListener().forEach {
                    it.onPageFinished(hybridUrl)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame != true) {
                    return
                }
                // 失败描述只是给人看的：宿主据以分支的是 phase 与 code，所以错误码与失败 URL
                // 都必须进统一错误模型。
                getLoadListener().forEach {
                    it.onLoadError(
                        HybridWebViewErrorMapper.loadError(
                            url = request.url?.toString()?.let { raw ->
                                InterceptionUrlMapper.systemRawUrlToHybridUrl(context, raw)
                            },
                            description = error?.description?.toString(),
                            nativeCode = error?.errorCode,
                            cause = Exception(error?.description?.toString())
                        )
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)

                getLoadListener().forEach {
                    if (request?.isForMainFrame == true) {
                        val rawUrl = request.url?.toString()
                        val hybridUrl = rawUrl?.let {
                            InterceptionUrlMapper.systemRawUrlToHybridUrl(context, it)
                        }
                        it.onSystemHttpError(
                            errorResponse?.statusCode ?: -1,
                            errorResponse?.reasonPhrase,
                            hybridUrl
                        )
                        // HTTP 失败要发两条：onSystemHttpError 是原有的业务口子（带 response，供页面层
                        // 使用），统一错误模型带 phase=HTTP 与 code=HTTP_<状态码>；两者语义不同，不是重复上报。
                        it.onLoadError(
                            HybridWebViewErrorMapper.httpError(
                                statusCode = errorResponse?.statusCode ?: -1,
                                url = hybridUrl,
                                description = errorResponse?.reasonPhrase
                            )
                        )
                    }
                }

            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                super.onReceivedSslError(view, handler, error)
                getLoadListener().forEach {
                    // onSystemSslError 供页面/宿主做证书决策，onLoadError 是统一错误模型；两条都要发。
                    it.onLoadError(
                        HybridWebViewErrorMapper.sslError(
                            url = error?.url,
                            description = error?.toString(),
                            cause = Exception(error?.toString())
                        )
                    )
                    it.onSystemSslError(error, handler)
                }
                handler?.cancel()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                // 请求 URL 属敏感数据（可能带 token/签名），必须受日志开关管辖。
                SdkLogger.d(
                    "SystemWebView",
                    "shouldInterceptRequest(WebResourceRequest) url=$url, " +
                        "isMainFrame=${request?.isForMainFrame}, method=${request?.method}"
                )
                return if (url != null) {
                    shouldInterceptRequest(
                        view = view,
                        url = url,
                        isForMainFrame = request?.isForMainFrame == true,
                        method = request?.method
                    )
                        ?: super.shouldInterceptRequest(view, request)
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            private fun shouldInterceptRequest(
                view: WebView?,
                url: String,
                isForMainFrame: Boolean,
                method: String?
            ): WebResourceResponse? {
                SdkLogger.d(
                    "SystemWebView",
                    "shouldInterceptRequest(String) start url=$url, isMainFrame=$isForMainFrame"
                )
                val uri = Uri.parse(url)

                // appassets.androidplatform.net 是本地资源域名：优先由 WebViewAssetLoader 处理，否则
                // 拦截规则返回 Allow(null) 时 WebView 会走网络通路，离线报 ERR_INTERNET_DISCONNECTED。
                if (uri.host == SdkConstants.ASSET_LOADER_HOST) {
                    val assetResponse = assetLoader.shouldInterceptRequest(uri)
                    if (assetResponse != null) return assetResponse
                }

                val localDomain = runCatching { LocalFileServer.buildBaseUrl(context) }.getOrNull()

                val hybridUrl = if (localDomain != null) {
                    val mapped = InterceptionUrlMapper.localDomainRawUrlToHybridUrl(context, localDomain, url)
                    if (mapped != url) mapped else InterceptionUrlMapper.systemRawUrlToHybridUrl(context, url)
                } else {
                    InterceptionUrlMapper.systemRawUrlToHybridUrl(context, url)
                }

                val compiled = compiledInterception
                if (compiled == null) {
                    return null
                }

                val action = RequestInterceptionResolver.resolveActionOrNull(
                    compiled = compiled,
                    url = hybridUrl,
                    isMainFrame = isForMainFrame,
                    method = method
                ) ?: run {
                    return null
                }

                return when (action) {
                    is RequestAction.Allow -> null
                    is RequestAction.Block -> buildBlockedResponse()
                    is RequestAction.Redirect -> buildRedirectOrServeResponse(action.targetUrl)
                    is RequestAction.ServeAsset -> serveAssetOrNull(action.assetPath)
                    is RequestAction.ServeFile -> serveFileOrNull(action.filesDirRelativePath)
                    is RequestAction.ServeBytes -> serveBytesOrNull(action.key)
                }
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)

                getLoadListener().forEach {
                    it.onProgressChanged(newProgress)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // 只摘掉自动打日志：LoadListener 回调必须原样保留，接入方仍要能拿到 console 事件。
                SdkLogger.d(
                    "SystemWebView",
                    "consoleMessage: ${consoleMessage?.message()} , level: ${consoleMessage?.messageLevel()}"
                )

                val level = when (consoleMessage?.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> HybridWebView.ConsoleLevel.LOG
                    ConsoleMessage.MessageLevel.WARNING -> HybridWebView.ConsoleLevel.WARNING
                    ConsoleMessage.MessageLevel.ERROR -> HybridWebView.ConsoleLevel.ERROR
                    ConsoleMessage.MessageLevel.DEBUG -> HybridWebView.ConsoleLevel.DEBUG
                    else -> HybridWebView.ConsoleLevel.LOG
                }

                getLoadListener().forEach { l ->
                    l.onSystemConsoleMessage(
                        consoleMessage?.message(),
                        consoleMessage?.lineNumber() ?: 0,
                        consoleMessage?.sourceId(),
                        level
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    /**
     * 把 Hybrid URL 翻译成本地域名 URL 后交给系统内核；「能不能渲染」的判断在 `HybridWebView.loadUrl`。
     */
    override fun loadUri(uri: String) {
        webView.loadUrl(resolveToLocalUrl(uri))
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String?
    ) {
        webView.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    override fun evaluateJavascript(script: String, callback: ((Any?) -> Unit)?) {
        if (callback != null) {
            webView.evaluateJavascript(script) { result ->
                callback(result)
            }
        } else {
            webView.evaluateJavascript(script, null)
        }
    }

    @SuppressLint("JavascriptInterface")
    override fun addJavascriptInterface(name: String, obj: Any) {
        webView.addJavascriptInterface(obj, name)
    }

    override fun removeJavascriptInterface(name: String) {
        webView.removeJavascriptInterface(name)
    }

    override fun getView(): View = webView

    override fun reload() {
        webView.reload()
    }

    override fun goBack() {
        webView.goBack()
    }

    override fun goForward() {
        webView.goForward()
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun stopLoading() {
        webView.stopLoading()
    }

    override fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        webView.clearCache(includeDiskFiles)
    }

    override fun getUrl(): String? {
        val raw = webView.url ?: return null
        return InterceptionUrlMapper.systemRawUrlToHybridUrl(context, raw)
    }

    override fun getTitle(): String? = webView.title

    override fun setUserAgent(userAgent: String) {
        webView.settings.userAgentString = userAgent
    }

    override fun updateRequestInterception(interception: RequestInterception?) {
        compiledInterception = RequestInterceptionResolver.compileOrNull(interception)
    }

    override fun doDestroy() {
        releaseServerRef()
        webView.destroy()
    }

    /**
     * 归还本地服务持有权；置空令牌保证重复销毁时 release 是 no-op，也不会误关另一个仍在显示的
     * 文档所用的服务。
     */
    private fun releaseServerRef() {
        val token = serverRefToken ?: return
        serverRefToken = null
        LocalFileServer.release(token)
    }

    override fun getLocalDomain(): String {
        return SdkConstants.ASSET_LOADER_BASE_URL
    }

    private fun buildBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by HybridWebView interceptor",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun buildRedirectOrServeResponse(targetUrl: String): WebResourceResponse? {
        val assetPath = InterceptionUrlMapper.parseAndroidAssetFileUrlOrNull(targetUrl)
        if (assetPath != null) return serveAssetOrNull(assetPath)

        val file = InterceptionUrlMapper.parseFilesDirFileOrNull(context, targetUrl)
        if (file != null) return serveFileOrNull(file.relativeTo(context.filesDir).path)

        val headers = mapOf("Location" to targetUrl)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            302,
            "Found",
            headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun serveAssetOrNull(assetPath: String): WebResourceResponse? {
        return try {
            val mimeType = MimeTypeHelper.getMimeType(assetPath)
            val input = context.assets.open(assetPath.trimStart('/'))
            WebResourceResponse(mimeType, "utf-8", 200, "OK", emptyMap(), input)
        } catch (_: Exception) {
            null
        }
    }

    private fun serveFileOrNull(filesDirRelativePath: String): WebResourceResponse? {
        return try {
            val file = java.io.File(context.filesDir, filesDirRelativePath.trimStart('/'))
            if (!file.exists() || !file.isFile) return null
            val mimeType = MimeTypeHelper.getMimeType(file.name)
            val input = FileInputStream(file)
            WebResourceResponse(mimeType, "utf-8", 200, "OK", emptyMap(), input)
        } catch (_: Exception) {
            null
        }
    }

    private fun serveBytesOrNull(key: String): WebResourceResponse? {
        val entry = LocalFileServer.getServeBytesOrNull(key) ?: return null
        val input = ByteArrayInputStream(entry.bytes)
        return WebResourceResponse(
            entry.mimeType,
            entry.encoding,
            entry.statusCode,
            "OK",
            entry.headers,
            input
        )
    }
}

/**
 * `/files/` 守门人：在 [WebViewAssetLoader.InternalStoragePathHandler] 之前拒掉 SDK 内部目录。
 * `<root>/meta/` 必须拦——页面读到 state.json 等于泄漏安装状态与版本；往里加前缀前要确认那东西
 * 真不该对页面可见，否则只是让一片可用路径变 404。判定前必须 `File.normalize()` 塌缩点段，
 * 否则 `geckoview/./meta/x` 会在前缀比对阶段绕过、再被委托方按真实路径命中被禁目录。
 */
private class SdkInternalPathBlockingHandler(
    private val delegate: WebViewAssetLoader.PathHandler
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val normalized = File(path.trimStart('/')).normalize().path
        if (DENIED_PREFIXES.any { normalized.startsWith(it) }) return null
        return delegate.handle(path)
    }

    private companion object {
        val DENIED_PREFIXES = listOf(
            "${GeckoSdk.ROOT_DIR_NAME}/meta/"
        )
    }
}

package com.happyboy.tokay.engine.webview

import android.content.Context
import android.net.http.SslError
import android.util.AttributeSet
import android.util.Log
import android.webkit.SslErrorHandler
import android.widget.FrameLayout
import com.happyboy.tokay.engine.webview.lifecycle.WebViewEventBus
import com.happyboy.tokay.engine.webview.lifecycle.WebViewStateManager
import com.happyboy.tokay.engine.webview.adapters.AdapterSwitcher
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception
import com.happyboy.tokay.engine.webview.ime.ImeInsetResizer

/**
 * 统一视图入口：按内核可用性在 GeckoView 与系统 WebView 之间自动选择与降级，对外只暴露
 * 与原生 `WebView` 尽量一致的调用方式。
 */
class HybridWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "HybridWebView"

        /**
         * 键盘避让的收尾：引擎在键盘刚弹出时就把聚焦元素滚好了，那时视口还没缩；等 padding 跟着
         * 动画缩完，元素可能又落到折线下方一点，这里补一次最小滚动。元素已经在可视区内时不动。
         */
        private const val NUDGE_FOCUSED_ELEMENT_SCRIPT = """
            (function() {
                var el = document.activeElement;
                if (!el) return "none";
                var tag = (el.tagName || "").toLowerCase();
                if (tag !== "input" && tag !== "textarea" && !el.isContentEditable) {
                    return "not_editable";
                }
                var rect = el.getBoundingClientRect();
                if (rect.bottom <= window.innerHeight && rect.top >= 0) return "visible";
                el.scrollIntoView({ block: "center" });
                return "scrolled";
            })();
        """
    }

    private val stateManager = WebViewStateManager()
    private val eventBus = WebViewEventBus()
    private val adapterSwitcher = AdapterSwitcher(context, this, stateManager, eventBus)

    private var loadListener: LoadListener? = null
    private val internalLoadListener = createInternalLoadListener()
    private var pageProgress: Int = 0
    private var loading: Boolean = false
    private val imeInsetResizer = ImeInsetResizer(this) { nudgeFocusedElementIntoView() }

    private val internalEventListener = object : WebViewEventBus.WebViewEventListener {
        override fun onAdapterSwitched(
            fromType: WebViewStateManager.WebViewType,
            toType: WebViewStateManager.WebViewType
        ) {
            updateAdapterListener()
        }
    }

    init {
        eventBus.addEventListener(internalEventListener)
        initializeWebView()
    }

    private fun initializeWebView() {
        if (adapterSwitcher.getCurrentAdapter() != null) return

        if (adapterSwitcher.initializeAdapter()) {
            updateAdapterListener()
            Log.d(TAG, "WebView初始化成功")
        } else {
            Log.e(TAG, "WebView初始化失败")
        }
    }

    private fun updateAdapterListener() {
        val adapter = adapterSwitcher.getCurrentAdapter() ?: return
        adapter.unregisterLoadListener(internalLoadListener)
        adapter.registerLoadListener(internalLoadListener)
    }

    private fun createInternalLoadListener(): LoadListener {
        return object : LoadListener {
            override fun onPageStarted(url: String?) {
                loading = true
                pageProgress = 0
                loadListener?.onPageStarted(url)
            }

            override fun onPageFinished(url: String?) {
                loading = false
                pageProgress = 100
                loadListener?.onPageFinished(url)
            }

            override fun onProgressChanged(progress: Int) {
                pageProgress = progress
                loadListener?.onProgressChanged(progress)
            }

            override fun onLoadError(error: HybridWebViewError) {
                // 只有"主文档根本没出来"才复位 loading：HTTP 4xx/5xx 与 SSL 的页面本身已经渲染过。
                if (error.phase == HybridWebViewError.Phase.LOAD ||
                    error.phase == HybridWebViewError.Phase.SSL
                ) {
                    loading = false
                }
                loadListener?.onLoadError(error)
            }

            override fun onSystemConsoleMessage(
                message: String?,
                lineNumber: Int,
                sourceId: String?,
                level: ConsoleLevel
            ) {
                loadListener?.onSystemConsoleMessage(message, lineNumber, sourceId, level)
            }

            override fun onSystemSslError(error: SslError?, handler: SslErrorHandler?) {
                loadListener?.onSystemSslError(error, handler)
            }

            override fun onSystemHttpError(
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                loadListener?.onSystemHttpError(errorCode, description, failingUrl)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        destroy()
    }

    /**
     * 释放全部内部资源（键盘避让、事件监听、底层适配器）。
     *
     * 从视图树移除时 `onDetachedFromWindow` 已经调用过，**不要"移除再复用"同一个实例**。
     */
    fun destroy() {
        imeInsetResizer.release()
        eventBus.removeEventListener(internalEventListener)
        adapterSwitcher.destroy()
        removeAllViews()

        eventBus.destroy()

        Log.d(TAG, "HybridWebView已销毁")
    }

    /** 传入 `null` 只移除业务侧监听，内部状态同步监听仍保留。 */
    fun setLoadListener(listener: LoadListener?) {
        this.loadListener = listener
        updateAdapterListener()
    }

    /**
     * 键盘避让开关（默认关闭）：开启后，键盘弹出时把 IME 高度作为底部 padding 施加在**本视图自己**身上，
     * 引擎会随之缩小可视区并把聚焦的输入框滚进来。
     *
     * 什么时候需要：窗口**边到边**（`WindowCompat.setDecorFitsSystemWindows(window, false)`）时系统
     * 没有可裁剪的窗口空间，系统的 `SOFT_INPUT_ADJUST_RESIZE` 不起作用，必须自己避让。普通窗口下
     * 系统已经缩过窗口了，这里拿到的 IME insets 是 0，开着无副作用。
     *
     * 什么时候别开：宿主已经自己处理键盘（例如 Compose `imePadding()`）。宿主消费掉 IME insets 时
     * 本方法同样拿到 0、不会叠加；但宿主只加 padding 而**没有消费** insets 时，两份会叠加出一条空白。
     *
     * 前置条件：宿主必须给本视图一个**确定高度**（match_parent / 固定 dp / weight）；`wrap_content`
     * 下加 padding 只会让它变高。API 30 以下没有 IME insets 分发，边到边窗口请改用系统 resize。
     */
    fun setImeResizeEnabled(enabled: Boolean) {
        imeInsetResizer.setEnabled(enabled)
    }

    private fun nudgeFocusedElementIntoView() {
        evaluateJavascript(NUDGE_FOCUSED_ELEMENT_SCRIPT, null)
    }

    fun reload() = adapterSwitcher.getCurrentAdapter()?.reload()

    fun goBack() = adapterSwitcher.getCurrentAdapter()?.goBack()

    fun goForward() = adapterSwitcher.getCurrentAdapter()?.goForward()

    fun canGoBack(): Boolean = adapterSwitcher.getCurrentAdapter()?.canGoBack() ?: false

    fun canGoForward(): Boolean = adapterSwitcher.getCurrentAdapter()?.canGoForward() ?: false

    fun stopLoading() = adapterSwitcher.getCurrentAdapter()?.stopLoading()

    /** 当前 URL，统一为 Hybrid URL 语义；尚未加载成功时可能为 `null`。 */
    fun getUrl(): String? = adapterSwitcher.getCurrentAdapter()?.getUrl()

    fun getTitle(): String? = adapterSwitcher.getCurrentAdapter()?.getTitle()

    fun getProgress(): Int = pageProgress

    fun isLoading(): Boolean = loading

    fun setUserAgent(userAgent: String) =
        adapterSwitcher.getCurrentAdapter()?.setUserAgent(userAgent)

    private fun updateRequestInterceptionInternal(interception: RequestInterception?) {
        Log.d(
            TAG,
            "设置请求拦截规则: enabled=${interception?.enabled}, rulesSize=${interception?.rules?.size ?: 0}, currentType=${stateManager.getCurrentWebViewType()}"
        )
        adapterSwitcher.updateRequestInterception(interception)
    }

    /**
     * 设置请求拦截规则（正则匹配）。
     *
     * 两个内核的实现不同：系统内核走 `shouldInterceptRequest`（可返回自定义响应体），GeckoView 走
     * WebExtension 的 `webRequest`（只能 block/redirect，其余能力靠重定向到本地服务补齐）。
     */
    fun setRequestInterception(interception: RequestInterception?) {
        updateRequestInterceptionInternal(interception)
    }

    /**
     * 类似原生 `WebView.loadDataWithBaseURL`。
     *
     * GeckoView 没有对应能力，内部落到本地 `/dyn/<token>` 加载（必要时给 HTML 注入 `<base href>`
     * 来模拟 [baseUrl]）。
     */
    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String = "text/html",
        encoding: String = "utf-8",
        historyUrl: String? = null
    ) {
        adapterSwitcher.getCurrentAdapter()?.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    fun loadData(
        data: String,
        mimeType: String = "text/html",
        encoding: String = "utf-8"
    ) {
        loadDataWithBaseURL(baseUrl = null, data = data, mimeType = mimeType, encoding = encoding, historyUrl = null)
    }

    /**
     * 执行 JS，结果经 [callback] 回传。**两个内核返回同一形状**：与原生 `WebView.evaluateJavascript`
     * 一致的 JSON 文本。
     *
     * | JS 结果 | 回调收到 |
     * |---|---|
     * | `"abc"` | `"abc"`（**带引号**的 JSON 字符串） |
     * | `2` / `true` | `2` / `true` |
     * | `({a:1})` / `[1,2]` | `{"a":1}` / `[1,2]`（对象结果**不带**外层引号） |
     * | `null` | `null`（四个字符的文本） |
     * | `undefined`、执行失败 | Kotlin `null` |
     *
     * 需要取值就自己解一次 JSON（`JSONObject(...)` / `JSONTokener`），这也是 WebView 宿主的既有写法。
     */
    fun evaluateJavascript(script: String, callback: ((Any?) -> Unit)? = null) =
        adapterSwitcher.getCurrentAdapter()?.evaluateJavascript(script, callback)

    /** 只暴露必要的方法，并且只在可信页面上调用。 */
    fun addJavascriptInterface(name: String, obj: Any) =
        adapterSwitcher.getCurrentAdapter()?.addJavascriptInterface(name, obj)

    fun removeJavascriptInterface(name: String) =
        adapterSwitcher.getCurrentAdapter()?.removeJavascriptInterface(name)

    /**
     * 加载 URL，支持三种写法：
     * - `http://` / `https://`：远程页面
     * - `hybrid://asset/<path>`：APK 内的 assets
     * - `hybrid://file/<path>`：应用私有目录（相对 `context.filesDir`）
     *
     * 具体走 WebViewAssetLoader 还是本地服务由适配器决定，调用方不必关心。
     */
    fun loadUrl(url: String) {
        adapterSwitcher.getCurrentAdapter()?.loadUri(url)
    }

    /** 建议在宿主 `onResume` 调用：恢复内核渲染。 */
    fun onResume() {
        adapterSwitcher.getCurrentAdapter()?.onResume()
    }

    /** 建议在宿主 `onPause` 调用：暂停内核渲染。 */
    fun onPause() {
        adapterSwitcher.getCurrentAdapter()?.onPause()
    }

    fun clearCache(includeDiskFiles: Boolean = true) {
        adapterSwitcher.getCurrentAdapter()?.clearCache(includeDiskFiles)
    }

    fun switchToGeckoView(): Boolean {
        val switched = adapterSwitcher.switchToGeckoView()
        if (switched) {
            updateAdapterListener()
        }
        return switched
    }

    fun switchToSystemWebView(): Boolean {
        val switched = adapterSwitcher.switchToSystemWebView()
        if (switched) {
            updateAdapterListener()
        }
        return switched
    }

    /** 切到当前推荐的内核（Gecko 可用时优先 Gecko）。 */
    fun switchToOptimalWebView(): Boolean {
        val switched = adapterSwitcher.switchToOptimalAdapter()
        if (switched) {
            updateAdapterListener()
        }
        return switched
    }

    fun getCurrentWebViewType(): WebViewStateManager.WebViewType =
        stateManager.getCurrentWebViewType()

    fun getAdapterStats(): AdapterSwitcher.AdapterStats = adapterSwitcher.getAdapterStats()

    /** 页面加载与失败回调；所有方法都带默认空实现，宿主只需覆写关心的那几个。 */
    interface LoadListener {
        fun onPageStarted(url: String?) {}

        fun onPageFinished(url: String?) {}

        fun onProgressChanged(progress: Int) {}

        /**
         * 主文档加载失败，覆盖两个内核的同一件事：Gecko 的导航失败、系统内核的主资源错误 /
         * HTTP 4xx-5xx / SSL 错误。
         *
         * 带默认空实现，新增回调不会破坏既有实现类——该性质由
         * `InterfaceDefaultMethodBinaryCompatTest` 钉住（它要求默认体是真正的 JVM 默认方法）。
         */
        fun onLoadError(error: HybridWebViewError) {}

        /** 控制台日志，主要来自系统内核。 */
        fun onSystemConsoleMessage(
            message: String?,
            lineNumber: Int,
            sourceId: String?,
            level: ConsoleLevel = ConsoleLevel.LOG
        ) {
        }

        fun onSystemSslError(error: SslError?, handler: SslErrorHandler?) {}

        fun onSystemHttpError(errorCode: Int, description: String?, failingUrl: String?) {}
    }

    enum class ConsoleLevel {
        LOG, WARNING, ERROR, DEBUG
    }
}

package com.happyboy.tokay.engine.webview.adapters

import android.view.View
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception

interface WebViewAdapter {
    fun loadUri(uri: String)
    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String? = null
    )
    fun evaluateJavascript(script: String, callback: ((Any?) -> Unit)? = null)
    fun addJavascriptInterface(name: String, obj: Any)
    fun removeJavascriptInterface(name: String)
    fun getView(): View
    fun getLocalDomain(): String

    fun reload()
    fun goBack()
    fun goForward()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun stopLoading()
    fun getUrl(): String?
    fun getTitle(): String?
    fun setUserAgent(userAgent: String)
    fun clearCache(includeDiskFiles: Boolean)
    fun onResume()
    fun onPause()
    fun destroy()
    fun registerLoadListener(listener: HybridWebView.LoadListener?)
    fun unregisterLoadListener(listener: HybridWebView.LoadListener?)
    fun getLoadListener(): List<HybridWebView.LoadListener>

    fun updateRequestInterception(interception: RequestInterception?)
}

package com.happyboy.tokay.engine.webview.lifecycle

import android.util.Log

class WebViewStateManager {
    
    companion object {
        private const val TAG = "WebViewStateManager"
    }
    
    private var currentWebViewType: WebViewType = WebViewType.UNKNOWN

    enum class WebViewType {
        SYSTEM, GECKO, UNKNOWN
    }
    
    fun setWebViewType(type: WebViewType) {
        val oldType = currentWebViewType
        if (oldType != type) {
            currentWebViewType = type
            Log.d(TAG, "WebView类型变化: $oldType -> $type")
        }
    }
    
    fun getCurrentWebViewType(): WebViewType = currentWebViewType
}

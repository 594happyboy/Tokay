package com.happyboy.tokay.engine.webview.lifecycle

import android.util.Log

/** WebView 级通用事件分发：适配器切换。 */
class WebViewEventBus {

    companion object {
        private const val TAG = "WebViewEventBus"
    }

    private val eventListeners = mutableListOf<WebViewEventListener>()

    interface WebViewEventListener {
        fun onAdapterSwitched(fromType: WebViewStateManager.WebViewType, toType: WebViewStateManager.WebViewType)
    }

    fun addEventListener(listener: WebViewEventListener) {
        if (!eventListeners.contains(listener)) {
            eventListeners.add(listener)
            Log.d(TAG, "事件监听器已添加")
        }
    }
    
    fun removeEventListener(listener: WebViewEventListener) {
        eventListeners.remove(listener)
        Log.d(TAG, "事件监听器已移除")
    }
    
    fun notifyAdapterSwitched(fromType: WebViewStateManager.WebViewType, toType: WebViewStateManager.WebViewType) {
        Log.d(TAG, "通知适配器切换: $fromType -> $toType")
        eventListeners.forEach { it.onAdapterSwitched(fromType, toType) }
    }
    
    fun destroy() {
        eventListeners.clear()
        Log.d(TAG, "事件总线已销毁")
    }
}

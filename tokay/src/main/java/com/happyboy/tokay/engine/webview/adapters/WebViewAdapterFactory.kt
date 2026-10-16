package com.happyboy.tokay.engine.webview.adapters

import android.content.Context
import android.util.Log
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.engine.webview.adapters.GeckoWebViewAdapter
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.lifecycle.WebViewStateManager

class WebViewAdapterFactory(
    private val context: Context,
    private val hybridWebView: HybridWebView
) {

    companion object {
        private const val TAG = "WebViewAdapterFactory"
    }

    fun createGeckoAdapter(): WebViewAdapter {
        return try {
            GeckoWebViewAdapter(context, hybridWebView)
        } catch (e: Exception) {
            Log.e(TAG, "创建GeckoView适配器失败", e)
            throw AdapterCreationException("GeckoView适配器创建失败", e)
        }
    }

    fun createSystemAdapter(): WebViewAdapter {
        Log.d(TAG, "创建系统WebView适配器")
        return try {
            SystemWebViewAdapter(context, hybridWebView)
        } catch (e: Exception) {
            Log.e(TAG, "创建系统WebView适配器失败", e)
            throw AdapterCreationException("系统WebView适配器创建失败", e)
        }
    }

    /** 优先 GeckoView，不可用时降级系统 WebView。 */
    fun createOptimalAdapter(): WebViewAdapter {
        Log.d(TAG, "创建最优适配器")
        return if (GeckoSdk.isInstalled) {
            try {
                createGeckoAdapter()
            } catch (e: Exception) {
                Log.w(TAG, "GeckoView不可用，降级到系统WebView", e)
                createSystemAdapter()
            }
        } else {
            Log.d(TAG, "GeckoView未安装，使用系统WebView")
            createSystemAdapter()
        }
    }

    fun createAdapter(type: WebViewStateManager.WebViewType): WebViewAdapter {
        return when (type) {
            WebViewStateManager.WebViewType.GECKO -> createGeckoAdapter()
            WebViewStateManager.WebViewType.SYSTEM -> createSystemAdapter()
            WebViewStateManager.WebViewType.UNKNOWN -> createOptimalAdapter()
        }
    }

    fun isAdapterAvailable(type: WebViewStateManager.WebViewType): Boolean {
        return try {
            when (type) {
                WebViewStateManager.WebViewType.GECKO -> GeckoSdk.isInstalled
                WebViewStateManager.WebViewType.SYSTEM -> true
                WebViewStateManager.WebViewType.UNKNOWN -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查适配器可用性失败: $type", e)
            false
        }
    }

    fun getRecommendedAdapterType(): WebViewStateManager.WebViewType {
        return if (GeckoSdk.isInstalled) {
            WebViewStateManager.WebViewType.GECKO
        } else {
            WebViewStateManager.WebViewType.SYSTEM
        }
    }

    class AdapterCreationException(message: String, cause: Throwable? = null) :
        Exception(message, cause)
}
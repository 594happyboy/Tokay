package com.happyboy.tokay.engine.webview.adapters

import android.content.Context
import android.util.Log
import android.view.View
import com.happyboy.tokay.engine.webview.adapters.GeckoWebViewAdapter
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.lifecycle.WebViewEventBus
import com.happyboy.tokay.engine.webview.lifecycle.WebViewStateManager
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception

class AdapterSwitcher(
    private val context: Context,
    private val hybridWebView: HybridWebView,
    private val stateManager: WebViewStateManager,
    private val eventBus: WebViewEventBus
) {

    companion object {
        private const val TAG = "AdapterSwitcher"
    }

    private val adapterFactory = WebViewAdapterFactory(context, hybridWebView)
    private var currentAdapter: WebViewAdapter? = null
    private var requestInterception: RequestInterception? = null

    fun switchAdapter(targetType: WebViewStateManager.WebViewType): Boolean {
        val currentType = stateManager.getCurrentWebViewType()

        if (currentType == targetType && currentAdapter != null) {
            Log.d(TAG, "适配器类型相同，无需切换: $targetType")
            return true
        }

        Log.d(TAG, "开始切换适配器: $currentType -> $targetType")

        return try {
            val newAdapter = adapterFactory.createAdapter(targetType)
            performSwitch(newAdapter, targetType)
            eventBus.notifyAdapterSwitched(currentType, targetType)

            Log.d(TAG, "适配器切换成功: $targetType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "适配器切换失败: $targetType", e)
            false
        }
    }

    fun switchToGeckoView(): Boolean {
        return switchAdapter(WebViewStateManager.WebViewType.GECKO)
    }

    fun switchToSystemWebView(): Boolean {
        return switchAdapter(WebViewStateManager.WebViewType.SYSTEM)
    }

    fun switchToOptimalAdapter(): Boolean {
        val recommendedType = adapterFactory.getRecommendedAdapterType()
        return switchAdapter(recommendedType)
    }

    fun initializeAdapter(): Boolean {
        Log.d(TAG, "初始化适配器")

        return try {
            val adapter = adapterFactory.createOptimalAdapter()
            // 类型以实际创建的适配器为准
            val adapterType = getAdapterType(adapter)
            performSwitch(adapter, adapterType)
            Log.d(TAG, "适配器初始化成功: $adapterType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "适配器初始化失败", e)
            try {
                val fallbackAdapter = adapterFactory.createSystemAdapter()
                performSwitch(
                    fallbackAdapter,
                    WebViewStateManager.WebViewType.SYSTEM
                )
                Log.d(TAG, "降级到系统WebView成功")
                true
            } catch (fallbackException: Exception) {
                Log.e(TAG, "降级到系统WebView也失败", fallbackException)
                false
            }
        }
    }

    private fun getAdapterType(adapter: WebViewAdapter): WebViewStateManager.WebViewType {
        return when (adapter) {
            is GeckoWebViewAdapter -> WebViewStateManager.WebViewType.GECKO
            is SystemWebViewAdapter -> WebViewStateManager.WebViewType.SYSTEM
            else -> WebViewStateManager.WebViewType.UNKNOWN
        }
    }

    private fun performSwitch(
        newAdapter: WebViewAdapter,
        targetType: WebViewStateManager.WebViewType
    ) {
        val oldAdapter = currentAdapter
        val oldType = stateManager.getCurrentWebViewType()
        val oldView = oldAdapter?.getView()
        val oldUrl = oldAdapter?.getUrl()

        val newView = newAdapter.getView()
        var newViewAdded = false

        try {
            // 插到最底层，避免盖住宿主后续加进来的覆盖层
            hybridWebView.addView(newView, 0)
            newViewAdded = true

            // 先隐藏旧 View（仍保留以便失败回滚）
            oldView?.visibility = View.GONE

            currentAdapter = newAdapter

            requestInterception?.let { interception ->
                newAdapter.updateRequestInterception(interception)
            }

            val actualType = if (targetType == WebViewStateManager.WebViewType.UNKNOWN) {
                getAdapterType(newAdapter)
            } else {
                targetType
            }
            stateManager.setWebViewType(actualType)
            Log.d(TAG, "设置WebView类型: $actualType")

            // 恢复 URL 刻意直呼适配器，不改走 hybridWebView.loadUrl：后者会形成
            // performSwitch → loadUrl → 拦截 → 切 Gecko → performSwitch 的重入，
            // 而外层还没 removeView / destroy，视图树与 currentAdapter 会被改坏。
            oldUrl?.let { newAdapter.loadUri(it) }

            // 新适配器就绪后才 removeView（从视图树移除即 destroy）并销毁旧适配器。
            if (oldAdapter != null && oldView != null) {
                hybridWebView.removeView(oldView)
                oldAdapter.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "performSwitch 失败，回滚到旧适配器", e)

            if (newViewAdded) {
                try {
                    hybridWebView.removeView(newView)
                } catch (_: Exception) {
                }
            }

            try {
                newAdapter.destroy()
            } catch (_: Exception) {
            }

            currentAdapter = oldAdapter
            stateManager.setWebViewType(oldType)
            oldView?.visibility = View.VISIBLE

            throw e
        }
    }

    fun getCurrentAdapter(): WebViewAdapter? = currentAdapter

    fun updateRequestInterception(interception: RequestInterception?) {
        requestInterception = interception
        currentAdapter?.updateRequestInterception(interception)
    }

    fun isAdapterAvailable(type: WebViewStateManager.WebViewType): Boolean {
        return adapterFactory.isAdapterAvailable(type)
    }

    fun destroy() {
        currentAdapter?.destroy()
        currentAdapter = null
        Log.d(TAG, "适配器切换器已销毁")
    }

    fun getAdapterStats(): AdapterStats {
        return AdapterStats(
            currentType = stateManager.getCurrentWebViewType(),
            geckoAvailable = isAdapterAvailable(WebViewStateManager.WebViewType.GECKO),
            systemAvailable = isAdapterAvailable(WebViewStateManager.WebViewType.SYSTEM),
            hasCurrentAdapter = currentAdapter != null
        )
    }

    data class AdapterStats(
        val currentType: WebViewStateManager.WebViewType,
        val geckoAvailable: Boolean,
        val systemAvailable: Boolean,
        val hasCurrentAdapter: Boolean
    )
}

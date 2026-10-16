package com.happyboy.tokay.scheduler

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.happyboy.tokay.GeckoSdk
import com.happyboy.tokay.engine.gecko.runtime.GeckoRuntimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

internal class PrewarmEngine(private val application: Application) {
    
    companion object {
        private const val TAG = "PrewarmEngine"
    }
    
    private var isWebViewPrewarmed = false
    private var prewarmedWebView: WebView? = null
    
    private var isGeckoViewPrewarmed = false
    private var isGeckoRuntimePrewarmed = false
    private var isGeckoSessionPrewarmed = false
    private var prewarmedRuntime: GeckoRuntime? = null
    private var prewarmedSession: GeckoSession? = null
    private var prewarmedGeckoView: GeckoView? = null

    suspend fun prewarmWebView(): Boolean {
        return try {
            if (isWebViewPrewarmed) {
                Log.d(TAG, "WebView已预热，跳过")
                return true
            }
            
            Log.i(TAG, "开始WebView预热")
            
            withContext(Dispatchers.Main) {
                prewarmedWebView = WebView(application).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        @Suppress("DEPRECATION")
                        databaseEnabled = true
                    }
                    loadUrl("about:blank")
                }
            }
            
            isWebViewPrewarmed = true
            Log.i(TAG, "WebView预热完成")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "WebView预热失败", e)
            false
        }
    }
    
    fun getWebView(): WebView? {
        return prewarmedWebView?.also {
            prewarmedWebView = null
            isWebViewPrewarmed = false
            Log.d(TAG, "WebView实例已被使用，状态已重置")
        }
    }
    
    fun isWebViewReady(): Boolean = isWebViewPrewarmed

    /** 预热 GeckoView；`aggressive=true` 时预热更多组件。 */
    suspend fun prewarmGeckoView(aggressive: Boolean = false): Boolean {
        return try {
            Log.i(TAG, "开始GeckoView预热 (aggressive=$aggressive)")
            
            if (!GeckoSdk.isInstalled) {
                Log.w(TAG, "GeckoSDK未准备就绪")
                return false
            }
            
            if (!isGeckoRuntimePrewarmed) {
                withContext(Dispatchers.Main) {
                    prewarmedRuntime = GeckoRuntimeProvider.getOrCreate(application)
                }
                isGeckoRuntimePrewarmed = true
                Log.d(TAG, "GeckoRuntime预热完成")
            }
            
            // 积极预热：同时预热 Session 和 View（View 不绑定 Session，便于外部组合）。
            if (aggressive && !isGeckoSessionPrewarmed) {
                withContext(Dispatchers.Main) {
                    val runtime = prewarmedRuntime ?: return@withContext

                    prewarmedSession =
                        com.happyboy.tokay.engine.gecko.runtime.GeckoSessionFactory.create(runtime)
                            .apply { loadUri("about:blank") }

                    if (!isGeckoViewPrewarmed) {
                        prewarmedGeckoView = GeckoView(application)
                        isGeckoViewPrewarmed = true
                        Log.d(TAG, "GeckoView实例预热完成")
                    }
                }
                isGeckoSessionPrewarmed = true
                Log.d(TAG, "GeckoSession预热完成")
            }
            
            Log.i(TAG, "Gecko 预热完成")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "GeckoView预热失败", e)
            false
        }
    }

    fun getGeckoView(): GeckoView? {
        return prewarmedGeckoView?.also {
            prewarmedGeckoView = null
            isGeckoViewPrewarmed = false
            Log.d(TAG, "GeckoView实例已被使用，状态已重置")
        }
    }

    fun getGeckoSession(): GeckoSession? {
        return prewarmedSession?.also {
            prewarmedSession = null
            isGeckoSessionPrewarmed = false
            Log.d(TAG, "GeckoSession实例已被使用，状态已重置")
        }
    }
    
    fun getGeckoRuntime(): GeckoRuntime? = prewarmedRuntime
    
    fun isGeckoViewReady(): Boolean = isGeckoViewPrewarmed
    
    fun isGeckoSessionReady(): Boolean = isGeckoSessionPrewarmed
    
    fun isGeckoRuntimeReady(): Boolean = isGeckoRuntimePrewarmed

    fun cleanup() {
        prewarmedWebView?.destroy()
        prewarmedWebView = null
        isWebViewPrewarmed = false
        
        prewarmedSession?.close()
        prewarmedSession = null
        prewarmedGeckoView = null
        isGeckoSessionPrewarmed = false
        isGeckoViewPrewarmed = false
        
        // Runtime 不主动 destroy，仅重置预热标记，允许后续重新预热。
        isGeckoRuntimePrewarmed = false
        
        Log.d(TAG, "预热引擎资源已清理")
    }
}

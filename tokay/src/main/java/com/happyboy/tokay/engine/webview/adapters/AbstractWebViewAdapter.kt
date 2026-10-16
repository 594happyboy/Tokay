package com.happyboy.tokay.engine.webview.adapters

import android.content.Context
import com.happyboy.tokay.engine.webview.HybridWebView
import com.happyboy.tokay.engine.webview.interceptor.InterceptionUrlMapper
import com.happyboy.tokay.infrastructure.config.SdkConstants

abstract class AbstractWebViewAdapter(
    protected val context: Context,
    protected val hybridWebView: HybridWebView
) : WebViewAdapter {

    private val loadListeners: MutableList<HybridWebView.LoadListener> = mutableListOf()


    final override fun registerLoadListener(listener: HybridWebView.LoadListener?) {
        listener?.let { loadListeners.add(it) }
    }

    final override fun unregisterLoadListener(listener: HybridWebView.LoadListener?) {
        loadListeners.remove(listener)
    }

    override fun getLoadListener(): List<HybridWebView.LoadListener> = loadListeners.toList()

    fun resolveToLocalUrl(path: String): String {
        val localHost = getLocalDomain()
        val trimmed = path.trim()
        return when {
            trimmed.startsWith("hybrid://") -> {
                InterceptionUrlMapper.hybridUrlToLocalDomainUrl(context, localHost, trimmed)
            }

            trimmed.startsWith(SdkConstants.ANDROID_ASSET_PATH) -> {
                val relative = trimmed.removePrefix(SdkConstants.ANDROID_ASSET_PATH).removePrefix("/")
                "$localHost/assets/$relative"
            }

            trimmed.startsWith("file://") &&
                trimmed.removePrefix("file://").startsWith(context.filesDir.absolutePath) -> {
                val filePath = trimmed.removePrefix("file://")
                val relativePath = filePath.removePrefix(context.filesDir.absolutePath)
                "$localHost/${context.filesDir.name}$relativePath"
            }

            else -> trimmed
        }
    }

    override fun destroy() {
        doDestroy()
    }

    protected abstract fun doDestroy()
}

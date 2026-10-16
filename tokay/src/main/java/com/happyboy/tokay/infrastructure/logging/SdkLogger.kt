package com.happyboy.tokay.infrastructure.logging

import android.util.Log
import com.happyboy.tokay.BuildConfig
import com.happyboy.tokay.LOG_TAG

/**
 * 统一日志工具类：统一的日志开关与格式化输出。
 *
 * release 包默认静默（见 [isDebugEnabled]），运行时可用 [setDebugEnabled] 覆盖。
 */
object SdkLogger {
    private const val TAG = LOG_TAG

    private var isDebugEnabled: Boolean = BuildConfig.DEBUG

    fun setDebugEnabled(enabled: Boolean) {
        isDebugEnabled = enabled
    }

    fun isDebugEnabled(): Boolean = isDebugEnabled

    fun d(module: String, message: String) {
        if (isDebugEnabled) {
            Log.d(TAG, "[$module] $message")
        }
    }

    fun i(module: String, message: String) {
        if (isDebugEnabled) {
            Log.i(TAG, "[$module] $message")
        }
    }

    fun w(module: String, message: String) {
        if (isDebugEnabled) {
            Log.w(TAG, "[$module] $message")
        }
    }

    fun e(module: String, message: String, throwable: Throwable? = null) {
        if (isDebugEnabled) {
            if (throwable != null) {
                Log.e(TAG, "[$module] $message", throwable)
            } else {
                Log.e(TAG, "[$module] $message")
            }
        }
    }

    fun logInterfaceCall(interfaceName: String, methodName: String, params: String = "") {
        val message = if (params.isNotEmpty()) {
            "Interface[$interfaceName.$methodName] called with params: $params"
        } else {
            "Interface[$interfaceName.$methodName] called"
        }
        d("InterfaceCall", message)
    }

    fun logNetworkRequest(url: String, method: String = "GET", status: String) {
        d("Network", "$method ${sanitizeUrl(url)} - $status")
    }

    /**
     * 去掉 URL 的 query：下载/代理地址的 query 里可能带签名、token 或资源标识，不应原样落到 logcat。
     */
    private fun sanitizeUrl(url: String): String {
        val queryIndex = url.indexOf('?')
        return if (queryIndex >= 0) {
            "${url.substring(0, queryIndex)}?<query 已脱敏>"
        } else {
            url
        }
    }

    fun logFileOperation(operation: String, filePath: String, result: String) {
        d("FileOp", "$operation: $filePath - $result")
    }
}
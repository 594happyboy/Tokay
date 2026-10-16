package com.happyboy.tokay.engine.webview.bridge

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一回调管理：原生提供的回调按 callbackId 登记，只有 [clear] 会清空这张表。
 * 消息类型名与 id 语义必须与 assets 侧的 bridge.page.js / bridge.plugin.js 保持一致。
 */
class CallbackManager {
    companion object {
        private const val TAG = LOG_TAG
        private var instance: CallbackManager? = null
        
        fun getInstance(): CallbackManager? = instance
        
        internal fun setInstance(manager: CallbackManager) {
            instance = manager
        }
    }
    
    private val callbackIdGenerator = AtomicLong(0)
    
    private val nativeProvidedCallbacks = ConcurrentHashMap<String, NativeCallbackInfo>()
    
    init {
        setInstance(this)
    }
    
    fun registerNativeProvidedCallback(
        callback: (Any?) -> Unit
    ): String {
        val callbackId = generateCallbackId()
        
        val callbackInfo = NativeCallbackInfo(callback = callback)
        
        nativeProvidedCallbacks[callbackId] = callbackInfo
        
        Log.d(TAG, "注册原生提供的回调: $callbackId")
        return callbackId
    }

    fun invokeCallback(callbackId: String, data: Any?) {
        val nativeCallback = nativeProvidedCallbacks[callbackId]
        if (nativeCallback != null) {
            try {
                nativeCallback.callback.invoke(data)
                Log.d(TAG, "调用原生提供的回调成功: $callbackId")
            } catch (e: Exception) {
                Log.e(TAG, "调用原生提供的回调失败: $callbackId", e)
            }
        }
    }
    
    fun clear() {
        nativeProvidedCallbacks.clear()
        Log.d(TAG, "清除所有回调")
    }
    
    private fun generateCallbackId(): String {
        val id = callbackIdGenerator.incrementAndGet()
        val timestamp = System.currentTimeMillis()
        return "callback_${id}_${timestamp}"
    }
}

data class NativeCallbackInfo(
    val callback: (Any?) -> Unit
)

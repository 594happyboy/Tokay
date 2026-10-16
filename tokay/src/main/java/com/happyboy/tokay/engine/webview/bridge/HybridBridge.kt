package com.happyboy.tokay.engine.webview.bridge

import android.content.Context
import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.engine.gecko.runtime.GeckoRuntimeProvider
import com.happyboy.tokay.engine.webview.interceptor.RequestInterception
import com.happyboy.tokay.engine.webview.interceptor.RequestInterceptionEnv
import com.happyboy.tokay.engine.webview.interceptor.toGeckoJson
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * 原生 ↔ JS 双向桥接。
 * 消息类型名与回调 id 语义是与 JS 侧对齐的契约，必须与 assets 侧的
 * bridge.page.js / bridge.plugin.js 保持一致。
 */
class HybridBridge(
    private val session: GeckoSession,
    private val context: Context
) {
    companion object {
        private const val TAG = LOG_TAG
        private const val EXT_COMMUNICATION = "resource://android/assets/runtime_extensions/communication/"
        private const val EXT_COMMUNICATION_ID = "communication@canqu.org"
    }

    private val interfaceManager = InterfaceManager()
    private val messageRouter = MessageRouter(interfaceManager)
    private val callbackManager = CallbackManager()

    private var port: WebExtension.Port? = null
    private var isReady = false

    private val registeredInterfaces = mutableSetOf<String>()

    private var onReadyListener: (() -> Unit)? = null

    fun initialize() {
        Log.d(TAG, "初始化混合桥接系统")
        setupWebExtension()
    }

    fun setOnReadyListener(listener: () -> Unit) {
        onReadyListener = listener
        if (isReady) {
            listener.invoke()
        }
    }

    fun addJavaScriptInterface(name: String, obj: Any) {
        interfaceManager.addInterface(name, obj)
        if (isReady) {
            registerInterface(name)
        }
    }

    fun removeJavaScriptInterface(name: String) {
        interfaceManager.removeInterface(name)
        registeredInterfaces.remove(name)
    }

    fun evaluateJavaScript(script: String, callback: ((Any?) -> Unit)? = null) {
        if (!isReady) {
            Log.w(TAG, "桥接未就绪，无法执行JavaScript")
            callback?.invoke(null)
            return
        }

        if (callback == null) {
            val message = JSONObject().apply {
                put(BridgeMessages.TYPE, BridgeMessages.EVALUATE_JS)
                put(BridgeMessages.FIELD_CODE, script)
            }
            sendMessage(message)
            return
        }

        val callbackId = callbackManager.registerNativeProvidedCallback(callback)

        val message = JSONObject().apply {
            put(BridgeMessages.TYPE, BridgeMessages.EVALUATE_JS)
            put(BridgeMessages.FIELD_ID, callbackId)
            put(BridgeMessages.FIELD_CODE, script)
        }
        sendMessage(message)
    }

    fun sendMessage(message: JSONObject) {
        try {
            port?.postMessage(message)
            Log.d(TAG, "发送消息: ${message.optString(BridgeMessages.TYPE)}")
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
        }
    }

    fun isReady(): Boolean {
        return isReady
    }

    fun updateRequestInterceptionRules(interception: RequestInterception, env: RequestInterceptionEnv) {
        updateRequestInterceptionRules(interception.toGeckoJson(env))
    }

    fun updateRequestInterceptionRules(rulesConfigJson: JSONObject) {
        if (!isReady) {
            Log.w(TAG, "桥接未就绪，无法更新拦截规则")
            return
        }

        try {
            val message = JSONObject().apply {
                put(BridgeMessages.TYPE, BridgeMessages.UPDATE_REQUEST_INTERCEPTION_RULES)
                put(BridgeMessages.FIELD_RULES_CONFIG, rulesConfigJson)
            }
            sendMessage(message)
            Log.d(TAG, "已下发请求拦截规则到 WebExtension: $rulesConfigJson")
        } catch (e: Exception) {
            Log.e(TAG, "更新请求拦截规则失败", e)
        }
    }

    fun destroy() {
        Log.d(TAG, "销毁混合桥接系统")
        port?.disconnect()
        port = null
        isReady = false
        registeredInterfaces.clear()
        callbackManager.clear()
        interfaceManager.clear()
    }

    fun reset() {
        Log.d(TAG, "重置混合桥接系统状态")
        isReady = false
        port?.disconnect()
        registeredInterfaces.clear()
        callbackManager.clear()
        // 接口定义跨页面保留，不随页面状态重置清理。
    }

    private fun setupWebExtension() {
        try {
            val runtime = GeckoRuntimeProvider.getOrCreate(context)
            runtime.webExtensionController.ensureBuiltIn(
                EXT_COMMUNICATION,
                EXT_COMMUNICATION_ID
            ).accept { extension ->
                if (extension != null) {
                    Log.d(TAG, "WebExtension加载成功: ${extension.id}")
                    setupMessageDelegate(extension)
                } else {
                    Log.e(TAG, "WebExtension加载失败")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置WebExtension失败", e)
        }
    }

    private fun setupMessageDelegate(extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "WebExtension连接建立: ${port.name}")
                    setupPortDelegate(port)
                }
            },
            "browser"
        )
    }

    private fun setupPortDelegate(port: WebExtension.Port) {
        this.port = port

        port.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                val msg = message as? JSONObject
                if (msg == null) {
                    Log.w(TAG, "收到非 JSONObject 消息，已忽略: ${message::class.java.name}")
                    return
                }
                handleMessage(msg)
            }

            override fun onDisconnect(port: WebExtension.Port) {
                Log.d(TAG, "WebExtension连接断开: ${port.name}")
                this@HybridBridge.port = null
                isReady = false
                registeredInterfaces.clear()
                Log.d(TAG, "已清理接口注册状态，等待重新连接")
            }
        })
    }

    private fun handleMessage(message: JSONObject) {
        val messageType = message.optString(BridgeMessages.TYPE)
        Log.d(TAG, "收到消息: $messageType")

        when (messageType) {
            BridgeMessages.BRIDGE_READY -> {
                handleBridgeReady()
            }

            else -> {
                messageRouter.routeMessage(message) { response ->
                    sendMessage(response)
                }
            }
        }
    }

    private fun handleBridgeReady() {
        Log.d(TAG, "桥接系统就绪")
        isReady = true

        interfaceManager.getAllInterfaceNames().forEach { name ->
            registerInterface(name)
        }

        onReadyListener?.invoke()
    }

    private fun registerInterface(name: String) {
        if (registeredInterfaces.contains(name)) {
            Log.d(TAG, "接口 $name 已经注册过，跳过重复注册")
            return
        }

        val methods = interfaceManager.getInterfaceMethods(name)
        if (methods.length() == 0) {
            Log.w(TAG, "接口 $name 没有可用方法")
            return
        }

        val message = JSONObject().apply {
            put(BridgeMessages.TYPE, BridgeMessages.REGISTER_INTERFACE)
            put(BridgeMessages.FIELD_NAME, name)
            put(BridgeMessages.FIELD_METHODS, methods)
        }

        sendMessage(message)
        registeredInterfaces.add(name)
        Log.d(TAG, "注册接口: $name")
    }

}

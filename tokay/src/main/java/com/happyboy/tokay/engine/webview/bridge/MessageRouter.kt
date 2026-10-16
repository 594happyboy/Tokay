package com.happyboy.tokay.engine.webview.bridge

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import org.json.JSONArray
import org.json.JSONObject

class MessageRouter(
    private val interfaceManager: InterfaceManager
) {
    companion object {
        private const val TAG = LOG_TAG
    }

    fun routeMessage(message: JSONObject, responseCallback: (JSONObject) -> Unit) {
        val messageType = message.optString(BridgeMessages.TYPE)
        Log.d(TAG, "路由消息: $messageType")
        
        when (messageType) {
            BridgeMessages.JS_TO_NATIVE_METHOD_CALL -> {
                handleJsToNativeMethodCall(message, responseCallback)
            }
            BridgeMessages.EVALUATE_RESULT -> {
                handleEvaluateResult(message)
            }
            BridgeMessages.EVALUATE_ERROR -> {
                handleEvaluateError(message)
            }

            BridgeMessages.INTERFACE_REGISTERED -> {
                handleInterfaceRegistered(message)
            }
            BridgeMessages.INTERFACE_CREATION_FAILED -> {
                handleInterfaceCreationFailed(message)
            }

            else -> {
                Log.w(TAG, "未知消息类型: $messageType")
            }
        }
    }

    private fun handleJsToNativeMethodCall(message: JSONObject, responseCallback: (JSONObject) -> Unit) {
        val interfaceName = message.optString(BridgeMessages.FIELD_INTERFACE)
        val methodName = message.optString(BridgeMessages.FIELD_METHOD)
        val callId = message.optString(BridgeMessages.FIELD_CALL_ID)
        val argsArray = message.optJSONArray(BridgeMessages.FIELD_ARGS) ?: JSONArray()
        
        Log.d(TAG, "处理JS->Native方法调用: $interfaceName.$methodName (callId: $callId)")
        
        val args = deserializeArguments(argsArray)
        
        val result = interfaceManager.invokeMethod(interfaceName, methodName, args)
        
        val response = createJsToNativeMethodCallResponse(callId, result)
        responseCallback(response)
    }
    
    private fun handleEvaluateResult(message: JSONObject) {
        val id = message.optString(BridgeMessages.FIELD_ID)
        val result = message.optJSONObject(BridgeMessages.FIELD_RESULT)
        
        Log.d(TAG, "处理JavaScript执行结果: $id")
        
        // 拆成与 WebView.evaluateJavascript 一致的 JSON 文本：Gecko 与 System 两个适配器
        // 因此返回同一形状（System 侧本来就是那串文本）
        CallbackManager.getInstance()?.invokeCallback(id, BridgeSerialization.evaluateResultToJsonText(result))
    }
    
    private fun handleEvaluateError(message: JSONObject) {
        val id = message.optString(BridgeMessages.FIELD_ID)
        
        Log.d(TAG, "处理JavaScript执行错误: $id")
        
        CallbackManager.getInstance()?.invokeCallback(id, null)
    }
    
    private fun handleInterfaceRegistered(message: JSONObject) {
        val name = message.optString(BridgeMessages.FIELD_NAME)
        Log.d(TAG, "接口注册成功: $name")
        
    }
    
    private fun handleInterfaceCreationFailed(message: JSONObject) {
        val name = message.optString(BridgeMessages.FIELD_NAME)
        val error = message.optString(BridgeMessages.FIELD_ERROR, "未知错误")
        
        Log.e(TAG, "接口创建失败: $name, 错误: $error")
    }

    private fun deserializeArguments(argsArray: JSONArray): Array<Any?> {
        return Array(argsArray.length()) { i ->
            val arg = argsArray.optJSONObject(i) ?: return@Array null
            
            val valueType = arg.optString(BridgeMessages.FIELD_VALUE_TYPE)
            when (valueType) {
                "null", "undefined" -> null
                "string" -> arg.optString(BridgeMessages.FIELD_VALUE)
                "json" -> {
                    val value = arg.optString(BridgeMessages.FIELD_VALUE)
                    try {
                        JSONObject(value)
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON字符串解析失败，返回原始字符串", e)
                        value
                    }
                }
                "object" -> {
                    arg.optJSONObject(BridgeMessages.FIELD_VALUE)
                }
                "number" -> {
                    val value = arg.optString(BridgeMessages.FIELD_VALUE)
                    value.toDoubleOrNull() ?: value
                }
                "boolean" -> {
                    val value = arg.optString(BridgeMessages.FIELD_VALUE)
                    value.toBoolean()
                }
                else -> arg.optString(BridgeMessages.FIELD_VALUE)
            }
        }
    }
    
    private fun createJsToNativeMethodCallResponse(
        callId: String,
        result: MethodInvocationResult
    ): JSONObject {
        return JSONObject().apply {
            put(BridgeMessages.TYPE, BridgeMessages.JS_TO_NATIVE_METHOD_RESPONSE)
            put(BridgeMessages.FIELD_CALL_ID, callId)
            
            when (result) {
                is MethodInvocationResult.Success -> {
                    put(BridgeMessages.FIELD_RESULT, BridgeSerialization.serializeResult(result.result))
                }
                is MethodInvocationResult.Error -> {
                    put(BridgeMessages.FIELD_ERROR, result.message)
                }
            }
        }
    }
}

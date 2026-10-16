package com.happyboy.tokay.engine.webview.bridge

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import org.json.JSONObject

/**
 * 原生 ↔ JS 的数据序列化；格式必须与 assets 侧的 bridge.page.js /
 * bridge.plugin.js 保持一致，两侧智能类型检测逻辑同源。
 *
 * 两个方向的包装形状不同，别混用：本文件的 [serializeResult] 发往 JS 时 `object` 的 value 是**嵌套对象**；
 * JS 侧 evaluate 结果里的 `json`，value 是 **JSON 文本字符串**。
 */
object BridgeSerialization {
    private const val TAG = LOG_TAG
    
    fun serializeResult(result: Any?): JSONObject {
        return JSONObject().apply {
            when (result) {
                null -> {
                    put("valueType", "null")
                }
                is String -> {
                    if (isJsonString(result)) {
                        put("valueType", "json")
                        put("value", result)
                    } else {
                        put("valueType", "string")
                        put("value", result)
                    }
                }
                is Number -> {
                    put("valueType", "number")
                    put("value", result)
                }
                is Boolean -> {
                    put("valueType", "boolean")
                    put("value", result)
                }
                is JSONObject -> {
                    put("valueType", "object")
                    put("value", result)
                }
                else -> {
                    try {
                        val jsonString = result.toString()
                        if (isJsonString(jsonString)) {
                            put("valueType", "json")
                            put("value", jsonString)
                        } else {
                            put("valueType", "string")
                            put("value", jsonString)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "序列化对象失败，使用toString()", e)
                        put("valueType", "string")
                        put("value", result.toString())
                    }
                }
            }
        }
    }
    
    private fun isJsonString(str: String): Boolean {
        if (str.isEmpty() || str.length < 2) return false
        
        val trimmed = str.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    /**
     * 把 JS 侧的 evaluate 结果包装还原成**与 `WebView.evaluateJavascript` 一样的 JSON 文本**。
     *
     * 同一个门面不该让宿主看到两个内核的差异：System 适配器拿到的就是 Android 的 JSON 文本
     * （字符串带引号、对象是其 JSON 文本），而 Gecko 侧原本把 `{valueType,value}` 包装对象直接交给了
     * 回调。这里把包装拆成同样的文本。
     *
     * `result` 为 null（执行失败，或消息里没有 result 字段）时返回 null。
     */
    fun evaluateResultToJsonText(result: JSONObject?): String? {
        if (result == null) return null
        return webViewEvaluateJsonText(
            valueType = result.optString(BridgeMessages.FIELD_VALUE_TYPE),
            value = if (result.has(BridgeMessages.FIELD_VALUE)) {
                result.optString(BridgeMessages.FIELD_VALUE)
            } else {
                null
            }
        )
    }
}

/**
 * [BridgeSerialization.evaluateResultToJsonText] 的纯逻辑部分（不碰 [JSONObject]，可直接单测）。
 *
 * @param valueType JS 侧包装里的 `valueType`；空值/未知类型都按字符串处理
 * @param value 包装里的 `value`；null 表示该包装**没有** value 字段（`null`/`undefined` 就没有）
 */
internal fun webViewEvaluateJsonText(valueType: String?, value: String?): String? = when (valueType) {
    // JS undefined：Android 的 WebView 回调同样给 null
    "undefined" -> null
    // JS null：JSON 文本就是四个字符的 "null"
    "null" -> "null"
    // 数字与布尔：JS 侧给的已经是合法 JSON 字面量，原样传
    "number", "boolean" -> value
    // 对象/数组：JS 侧给的是 JSON 文本，原样传（Android 对对象结果也不加外层引号）
    "json", "object" -> value
    // 字符串（含未知类型）：必须补引号并转义，否则宿主分不清 "2" 与 2
    else -> JSONObject.quote(value ?: "")
}



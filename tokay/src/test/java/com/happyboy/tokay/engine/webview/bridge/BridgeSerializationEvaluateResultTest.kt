package com.happyboy.tokay.engine.webview.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归护栏：`evaluateJavascript` 的回调必须在**两个内核上形状一致**——都是与
 * `WebView.evaluateJavascript` 一样的 JSON 文本。
 *
 * 这里钉住 Gecko 侧的拆包：JS 侧包装（`{valueType,value}`）经 [BridgeSerialization.evaluateResultToJsonText]
 * 之后，必须与 Android 平台对同一 JS 结果给出的文本逐字相同。System 适配器不经过本函数
 * （它拿到的本来就是那串文本），所以这里也就是「两个适配器一致」的定义。
 */
class BridgeSerializationEvaluateResultTest {

    private fun jsonTextOf(valueType: String, value: Any? = null): String? =
        BridgeSerialization.evaluateResultToJsonText(
            JSONObject().apply {
                put(BridgeMessages.FIELD_VALUE_TYPE, valueType)
                if (value != null) put(BridgeMessages.FIELD_VALUE, value)
            }
        )

    @Test
    fun `字符串补引号`() {
        assertEquals("\"abc\"", jsonTextOf("string", "abc"))
        assertEquals("\"\"", jsonTextOf("string", ""))
    }

    @Test
    fun `字符串里的引号与控制字符被转义`() {
        assertEquals("\"x\\\"y\"", jsonTextOf("string", "x\"y"))
        assertEquals("\"a\\nb\"", jsonTextOf("string", "a\nb"))
        assertEquals("\"a\\\\b\"", jsonTextOf("string", "a\\b"))
    }

    @Test
    fun `数字与布尔原样传`() {
        assertEquals("2", jsonTextOf("number", 2))
        assertEquals("2.5", jsonTextOf("number", 2.5))
        assertEquals("true", jsonTextOf("boolean", true))
    }

    @Test
    fun `对象与数组是其 JSON 文本，不带外层引号`() {
        assertEquals("{\"a\":1}", jsonTextOf("json", "{\"a\":1}"))
        assertEquals("[1,2]", jsonTextOf("json", "[1,2]"))
        // 理论上不该出现（JS 侧发的是 json），但兼容旧的 object 写法
        assertEquals("{\"a\":1}", jsonTextOf("object", "{\"a\":1}"))
    }

    @Test
    fun `null 是四个字符的文本，undefined 是 Kotlin null`() {
        assertEquals("null", jsonTextOf("null"))
        assertNull(jsonTextOf("undefined"))
    }

    @Test
    fun `没有包装时返回 null`() {
        assertNull(BridgeSerialization.evaluateResultToJsonText(null))
    }

    @Test
    fun `未知类型按字符串处理，不会把裸值漏给宿主`() {
        assertEquals("\"2\"", jsonTextOf("someFutureType", "2"))
    }
}

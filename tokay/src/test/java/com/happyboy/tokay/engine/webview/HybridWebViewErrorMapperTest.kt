package com.happyboy.tokay.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HybridWebViewErrorMapper] 的输入 → 阶段映射，覆盖 Gecko / 系统内核加载错误、HTTP 错误、SSL 错误。
 * 每条都断言 `code` 非空——宿主拿 `code` 分支，空串等于把失败变成静默。
 */
class HybridWebViewErrorMapperTest {

    @Test
    fun `HTTP 4xx 映射为 HTTP 且 code 带上状态码`() {
        val error = HybridWebViewErrorMapper.httpError(
            statusCode = 404,
            url = "hybrid://asset/nope.html",
            description = "Not Found"
        )

        assertEquals(HybridWebViewError.Phase.HTTP, error.phase)
        assertEquals("HTTP_404", error.code)
        assertTrue(error.code.isNotBlank())
        assertEquals(404, error.httpStatusCode)
        // URL 不能在映射时被丢掉
        assertEquals("hybrid://asset/nope.html", error.url)
    }

    @Test
    fun `SSL 错误映射为 SSL 且 code 非空`() {
        val error = HybridWebViewErrorMapper.sslError(
            url = "https://self-signed.example.com/index.html",
            description = "SSL_DATE_INVALID"
        )

        assertEquals(HybridWebViewError.Phase.SSL, error.phase)
        assertEquals(HybridWebViewErrorMapper.CODE_SSL_ERROR, error.code)
        assertTrue(error.code.isNotBlank())
    }

    @Test
    fun `内核导航错误映射为 LOAD 且保留原生码与 URL`() {
        val error = HybridWebViewErrorMapper.loadError(
            url = "hybrid://asset/nope.html",
            description = "The resource could not be loaded",
            nativeCode = 2
        )

        assertEquals(HybridWebViewError.Phase.LOAD, error.phase)
        assertEquals(HybridWebViewErrorMapper.CODE_LOAD_ERROR, error.code)
        assertEquals(2, error.nativeCode)
        assertEquals("hybrid://asset/nope.html", error.url)
        assertTrue(error.describe().contains("phase=LOAD"))
    }

    @Test
    fun `statusCode 非法时 code 退回 UNKNOWN 而不是空串或 HTTP_-1`() {
        val error = HybridWebViewErrorMapper.httpError(statusCode = -1, url = null)
        assertEquals(HybridWebViewErrorMapper.CODE_UNKNOWN, error.code)
        assertTrue(error.code.isNotBlank())
    }
}

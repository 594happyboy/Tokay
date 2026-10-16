package com.happyboy.tokay.engine.gecko.delegates

import com.happyboy.tokay.engine.webview.HybridWebViewError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

/**
 * Gecko 的 `WebRequestError` 归一：SSL 类错误必须归为 SSL 而非 LOAD，
 * 否则两条内核路径对同一件事（证书无效）会给出不同的 phase，宿主无法区分。
 */
class GeckoDelegatesTest {

    private fun error(code: Int, category: Int) = WebRequestError(code, category)

    @Test
    fun `SSL 错误码归为 SSL 而不是 LOAD`() {
        val ssl = GeckoDelegates.toLoadError(
            "https://self-signed.example.com/index.html",
            error(WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_CATEGORY_SECURITY)
        )
        assertEquals(HybridWebViewError.Phase.SSL, ssl.phase)
        assertEquals("https://self-signed.example.com/index.html", ssl.url)
    }

    @Test
    fun `坏证书错误码归为 SSL`() {
        val badCert = GeckoDelegates.toLoadError(
            "https://expired.example.com/index.html",
            error(WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_CATEGORY_SECURITY)
        )
        assertEquals(HybridWebViewError.Phase.SSL, badCert.phase)
    }

    @Test
    fun `category 为安全类时即使 code 不认识也归为 SSL`() {
        // 防御：将来 Gecko 新增安全类错误码时 code 白名单会漏判，category 是兜底。
        val unknownSecurity = GeckoDelegates.toLoadError(
            "https://example.com/",
            error(WebRequestError.ERROR_UNKNOWN, WebRequestError.ERROR_CATEGORY_SECURITY)
        )
        assertEquals(HybridWebViewError.Phase.SSL, unknownSecurity.phase)
    }

    @Test
    fun `网络类错误仍归为 LOAD 并保留原生码`() {
        val unknownHost = GeckoDelegates.toLoadError(
            "hybrid://asset/nope.html",
            error(WebRequestError.ERROR_UNKNOWN_HOST, WebRequestError.ERROR_CATEGORY_NETWORK)
        )
        assertEquals(HybridWebViewError.Phase.LOAD, unknownHost.phase)
        assertEquals(WebRequestError.ERROR_UNKNOWN_HOST, unknownHost.nativeCode)
        assertEquals("hybrid://asset/nope.html", unknownHost.url)
    }
}

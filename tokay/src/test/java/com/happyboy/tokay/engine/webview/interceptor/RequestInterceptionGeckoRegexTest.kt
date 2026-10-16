package com.happyboy.tokay.engine.webview.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归护栏：Gecko 侧的规则最终由 `background.js` 的 `new RegExp` 编译，所以 native 拼进正则源码的
 * 字面量前缀必须是 **JS** 认识的正则。这里钉住两件事：不能出现 Java 专有的 `\Q…\E`
 * （JS 会把它当成字面量 Q/E，规则静默永不命中），以及前缀里的元字符确实被转义。
 */
class RequestInterceptionGeckoRegexTest {

    private val env = RequestInterceptionEnv(
        localDomain = "http://127.0.0.1:28471",
        filesDirName = "files"
    )

    @Test
    fun `asset 前缀被换成转义后的本地地址，且不含 Java 专有引用`() {
        val gecko = rewriteHybridPrefixesForGecko("^hybrid://asset/[^?]*\\.js$", env)

        assertFalse(
            "不能出现 \\Q…\\E：JS 只当字面量 Q/E，规则会静默失效",
            gecko.contains("\\Q") || gecko.contains("\\E")
        )
        assertTrue("前缀里的点号必须被转义", gecko.contains("127\\.0\\.0\\.1:28471"))
        assertTrue(
            "替换后的规则要能匹配本地服务真实 URL: $gecko",
            Regex(gecko).matches("http://127.0.0.1:28471/assets/app.js")
        )
    }

    @Test
    fun `file 前缀被换成 filesDir 地址`() {
        val gecko = rewriteHybridPrefixesForGecko("^hybrid://file/index\\.html$", env)

        // 斜杠也被转义（`\/` 在 JS 的非 unicode 与 unicode 模式下都是合法转义），所以整个替换片段是
        // 一段既能进 `new RegExp` 也能进正则字面量的源码。
        assertEquals("^http:\\/\\/127\\.0\\.0\\.1:28471\\/files\\/index\\.html$", gecko)
        assertTrue(Regex(gecko).matches("http://127.0.0.1:28471/files/index.html"))
    }

    @Test
    fun `转义写法与未转义写法替换结果一致`() {
        assertEquals(
            rewriteHybridPrefixesForGecko("^hybrid://asset/.*$", env),
            rewriteHybridPrefixesForGecko("^hybrid:\\/\\/asset\\/.*$", env)
        )
    }

    @Test
    fun `escapeForJsRegex 覆盖全部 JS 正则元字符`() {
        assertEquals(
            "\\.\\*\\+\\?\\^\\$\\{\\}\\(\\)\\|\\[\\]\\\\\\/",
            escapeForJsRegex(".*+?^${'$'}{}()|[]\\/")
        )
    }
}

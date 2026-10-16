package com.happyboy.tokay.engine.server.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `content://` 流式映射的纯逻辑单测（路径编解码、前缀判定、scheme 白名单）。
 * `getFile()` 的后半段要调 `Uri.parse` 与 `ContentResolver`，而本项目没有 Robolectric，本地 JVM 里
 * 一调就抛 "not mocked"；所以只钉住它之前的校验——恰好都是安全相关的部分。
 */
class ContentUriMappingModuleTest {

    private val sample = "content://com.example.app.fileprovider/geckoview/doc.docx"

    // 前缀语义

    @Test
    fun handles_onlyContentPrefix() {
        assertTrue(ContentUriMappingModule.handles("/content/abc"))
        assertFalse("不得抢 /assets/", ContentUriMappingModule.handles("/assets/x"))
        assertFalse("不得抢 /files/", ContentUriMappingModule.handles("/files/x"))
        assertFalse("不得抢根路径", ContentUriMappingModule.handles("/"))
        assertFalse("前缀必须完整匹配，/contentX 不算", ContentUriMappingModule.handles("/contentX/a"))
    }

    // 编解码往返

    @Test
    fun buildPath_then_extract_roundTrips() {
        val path = ContentUriMappingModule.buildPath(sample)
        assertTrue(path.startsWith("/content/"))
        assertEquals(sample, ContentUriMappingModule.extractUriString(path))
    }

    @Test
    fun percentEncode_usesPathSemantics_notFormSemantics() {
        // URLEncoder 会把空格编成 `+`，而 `+` 在路径段里是字面量；改成 URLEncoder 这条会红。
        assertEquals("%20", ContentUriMappingModule.percentEncode(" "))
        assertFalse(
            "空格绝不能编成 +（form 语义会破坏路径）",
            ContentUriMappingModule.percentEncode(" ").contains('+')
        )
    }

    @Test
    fun percentDecode_keepsPlusAsLiteral() {
        assertEquals("+", ContentUriMappingModule.percentDecode("+"))
        assertEquals("a+b", ContentUriMappingModule.percentDecode("a+b"))
        assertEquals("+", ContentUriMappingModule.percentDecode("%2B"))
    }

    @Test
    fun roundTrip_preservesSlashColonSpacePlusAndUnicode() {
        val tricky = "content://p/路径 with space+plus/文件 (1).docx"
        val path = ContentUriMappingModule.buildPath(tricky)
        // `/` 与 `:` 必须被编码，否则会多出路径层级、或与 scheme 语法冲突
        assertFalse("斜杠必须编码，否则 /content/ 会多出层级", path.removePrefix("/content/").contains('/'))
        assertEquals(tricky, ContentUriMappingModule.extractUriString(path))
    }

    // 拒绝分支

    @Test
    fun extractUriString_rejectsWrongPrefixAndEmptyTail() {
        assertNull(ContentUriMappingModule.extractUriString("/assets/x"))
        assertNull("前缀后为空应拒绝", ContentUriMappingModule.extractUriString("/content/"))
    }

    @Test
    fun extractUriString_rejectsMalformedPercentEncoding() {
        assertNull("`%` 后不足两位", ContentUriMappingModule.extractUriString("/content/content%3"))
        assertNull("`%` 后不是十六进制", ContentUriMappingModule.extractUriString("/content/content%ZZ"))
    }

    @Test
    fun extractUriString_rejectsTraversal() {
        // 即便 provider 路径里出现 `..`，也不该由本模块解析
        assertNull(ContentUriMappingModule.extractUriString("/content/content%3A%2F%2Fp%2F..%2Fetc"))
        assertNull(ContentUriMappingModule.extractUriString("/content/../secret"))
    }

    @Test
    fun isContentUri_rejectsOtherSchemes() {
        assertTrue(ContentUriMappingModule.isContentUri("content://p/a"))
        assertTrue("scheme 大小写不敏感", ContentUriMappingModule.isContentUri("CONTENT://p/a"))
        assertFalse("file:// 不得由本模块处理", ContentUriMappingModule.isContentUri("file:///etc/hosts"))
        assertFalse("http:// 不得由本模块处理（否则变成 SSRF 面）", ContentUriMappingModule.isContentUri("http://evil/x"))
        assertFalse(ContentUriMappingModule.isContentUri("/content/abc"))
    }

    @Test
    fun defaultMimeType_isNotHtml() {
        // 兜底 MIME 若是 text/html，浏览器会把任意 provider 内容当页面执行
        assertEquals("application/octet-stream", ContentUriMappingModule.DEFAULT_MIME_TYPE)
    }
}

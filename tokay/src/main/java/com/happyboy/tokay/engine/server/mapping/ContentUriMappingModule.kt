package com.happyboy.tokay.engine.server.mapping

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.happyboy.tokay.engine.server.MimeTypeHelper
import java.io.InputStream

/**
 * 把 `content://` URI **流式**送进本地服务，路径形态 `<localHost>/content/<percent-encoded URI>`。
 * 不落盘、不进 `/dyn`（那是「整份内存字节」语义）；取不到流一律返回 `null` 让上层照常回 404，
 * 不把「文件不存在」与「没权限」在响应上区分出来。
 * 已知限制：`SimpleHttpServer` 不支持 Range 请求，大文件仍是一次性推完。
 */
class ContentUriMappingModule(
    private val context: Context,
    private val baseUrl: String = BASE_URL,
    private val openStream: (Uri) -> InputStream? = { uri ->
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    },
    private val queryMimeType: (Uri) -> String? = { uri ->
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }
) : FileMappingModule {

    override fun canHandle(path: String): Boolean = handles(path, baseUrl)

    override fun getFile(path: String): FileMappingResult? {
        val raw = extractUriString(path, baseUrl) ?: return null
        if (!isContentUri(raw)) return null

        // 到这里才碰 Android（Uri.parse / ContentResolver）：本仓没有 Robolectric，
        // `Uri.parse` 在 JVM 单测里是未实现的 stub，所以上面的校验保持纯字符串逻辑。
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

        // MIME 拿不到不是致命：给一个保守默认值，让浏览器按二进制处理而不是猜成 HTML。
        val mimeType = queryMimeType(uri)?.takeIf { it.isNotBlank() }
            ?: MimeTypeHelper.getMimeType(uri.lastPathSegment.orEmpty())
                .takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME_TYPE

        val stream = openStream(uri) ?: return null
        return FileMappingResult(mimeType, stream)
    }

    companion object {
        const val BASE_URL = "/content/"

        /** provider 没给类型、且后缀也认不出时的兜底；**不要**给 `text/html`。 */
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        /** 只接受 `content://`（纯函数）：别的 scheme 不在这里兜底，否则会变成任意 URL 拉取面（SSRF）。 */
        fun isContentUri(raw: String): Boolean =
            raw.startsWith("$SCHEME_CONTENT://", ignoreCase = true)

        fun handles(path: String, baseUrl: String = BASE_URL): Boolean = path.startsWith(baseUrl)

        private const val SCHEME_CONTENT = "content"

        fun buildPath(uriString: String, baseUrl: String = BASE_URL): String =
            baseUrl + percentEncode(uriString)

        /** 从模块路径还原 `content://` URI；前缀不匹配或解码失败返回 `null`。
         *  请求路径被 `SimpleHttpServer` 原样透传，所以这里必须自己解码。 */
        fun extractUriString(path: String, baseUrl: String = BASE_URL): String? {
            if (!path.startsWith(baseUrl)) return null
            val encoded = path.removePrefix(baseUrl)
            if (encoded.isEmpty()) return null

            // `%2F` 可能被中间层解成 `/` 而多出层级：先拒绝裸 `../`，再解码，
            // 解码后仍是 content:// 才接受。
            if (path.contains("..")) return null
            val decoded = percentDecode(encoded) ?: return null
            return decoded.takeIf { it.isNotEmpty() }
        }

        // 按 path 语义编解码（空格 → `%20`）：`java.net.URLEncoder` 是 form 语义（空格 → `+`），
        // 而 `+` 在路径段里是字面量，会被 `URLDecoder` 还原成空格、破坏 URI。

        private val UNRESERVED: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.', '~')).toSet()

        /** path 语义的 percent-encode：除 RFC 3986 unreserved 外全部编码。 */
        fun percentEncode(value: String): String = buildString(value.length * 3) {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in UNRESERVED) {
                    append(char)
                } else {
                    append('%')
                    append(HEX[(byte.toInt() and 0xF0) ushr 4])
                    append(HEX[byte.toInt() and 0x0F])
                }
            }
        }

        /** percent-decode；格式非法（`%` 后不是两位十六进制）返回 `null`，不抛。 */
        fun percentDecode(value: String): String? {
            val out = java.io.ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= value.length) return null
                        val hi = Character.digit(value[i + 1], 16)
                        val lo = Character.digit(value[i + 2], 16)
                        if (hi < 0 || lo < 0) return null
                        out.write((hi shl 4) or lo)
                        i += 3
                    }
                    else -> {
                        out.write(c.toString().toByteArray(Charsets.UTF_8))
                        i += 1
                    }
                }
            }
            return runCatching { out.toByteArray().toString(Charsets.UTF_8) }.getOrNull()
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}

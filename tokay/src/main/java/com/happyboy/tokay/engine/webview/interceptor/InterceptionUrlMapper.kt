package com.happyboy.tokay.engine.webview.interceptor

import android.content.Context
import android.net.Uri
import com.happyboy.tokay.infrastructure.config.SdkConstants
import java.io.File

/**
 * 请求拦截 URL 映射：外部只面对统一的 Hybrid URL（`http(s)://`、`hybrid://asset/...`、
 * `hybrid://file/...`），内部则按内核不同出现 `https://appassets.androidplatform.net/assets/...`
 * （System）或 `http://127.0.0.1:<port>/assets/...`（Gecko）。本类做这两条通路的双向映射。
 */
internal object InterceptionUrlMapper {
    private const val HYBRID_SCHEME = "hybrid"
    private const val HYBRID_AUTHORITY_ASSET = "asset"
    private const val HYBRID_AUTHORITY_FILE = "file"

    fun buildHybridAssetUrl(relativePath: String): String =
        "$HYBRID_SCHEME://$HYBRID_AUTHORITY_ASSET/${relativePath.trimStart('/')}"

    fun buildHybridFileUrl(relativePath: String): String =
        "$HYBRID_SCHEME://$HYBRID_AUTHORITY_FILE/${relativePath.trimStart('/')}"

    fun systemRawUrlToHybridUrl(context: Context, rawUrl: String): String {
        val uri = Uri.parse(rawUrl)
        if (uri.host != SdkConstants.ASSET_LOADER_HOST) {
            return rawUrl
        }

        val path = uri.path ?: return rawUrl
        return when {
            path.startsWith("/assets/") -> {
                val relative = path.removePrefix("/assets/")
                buildHybridAssetUrl(relative)
            }

            path.startsWith("/${context.filesDir.name}/") -> {
                val relative = path.removePrefix("/${context.filesDir.name}/")
                buildHybridFileUrl(relative)
            }

            else -> rawUrl
        }
    }

    /** 映射为当前 localDomain 下的实际请求 URL；仅映射 filesDir 下的 `file://`，其余原样返回。 */
    fun hybridUrlToLocalDomainUrl(
        context: Context,
        localDomain: String,
        hybridUrlOrPattern: String
    ): String {
        val uri = Uri.parse(hybridUrlOrPattern)
        if (uri.scheme == HYBRID_SCHEME) {
            val path = uri.path.orEmpty().trimStart('/')
            return when (uri.authority) {
                HYBRID_AUTHORITY_ASSET -> "$localDomain/assets/$path"
                HYBRID_AUTHORITY_FILE -> "$localDomain/${context.filesDir.name}/$path"
                else -> hybridUrlOrPattern
            }
        }
        return hybridUrlOrPattern
    }

    fun parseAndroidAssetFileUrlOrNull(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != HYBRID_SCHEME || uri.authority != HYBRID_AUTHORITY_ASSET) return null
        return uri.path?.trimStart('/')
    }

    /** 解析 filesDir 下的 file URL，仅允许 filesDir 下。 */
    fun parseFilesDirFileOrNull(context: Context, url: String): File? {
        val uri = Uri.parse(url)
        if (uri.scheme != HYBRID_SCHEME || uri.authority != HYBRID_AUTHORITY_FILE) return null
        val relative = uri.path.orEmpty().trimStart('/')
        return File(context.filesDir, relative)
    }

    fun localDomainRawUrlToHybridUrl(
        context: Context,
        localDomain: String,
        rawUrl: String
    ): String {
        val uri = Uri.parse(rawUrl)
        val localUri = Uri.parse(localDomain)
        if (uri.scheme != localUri.scheme || uri.host != localUri.host || uri.port != localUri.port) {
            return rawUrl
        }
        val path = uri.path ?: return rawUrl
        return when {
            path.startsWith("/assets/") -> {
                val relative = path.removePrefix("/assets/")
                buildHybridAssetUrl(relative)
            }
            path.startsWith("/${context.filesDir.name}/") -> {
                val relative = path.removePrefix("/${context.filesDir.name}/")
                buildHybridFileUrl(relative)
            }
            else -> rawUrl
        }
    }
}

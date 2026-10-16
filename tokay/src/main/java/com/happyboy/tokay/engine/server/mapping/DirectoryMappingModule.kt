package com.happyboy.tokay.engine.server.mapping

import com.happyboy.tokay.engine.server.MimeTypeHelper
import java.io.File

class DirectoryMappingModule(
    private val directory: File,
    private val baseUrl: String = "/files/"
) : FileMappingModule {

    override fun canHandle(path: String): Boolean {
        return path.startsWith(baseUrl)
    }

    override fun getFile(path: String): FileMappingResult? {
        val relativePath = path.removePrefix(baseUrl)
        if (relativePath.isEmpty()) return null

        // 请求路径被原样透传（不解码、不规范化），`../` 或绝对路径能让 File(directory, path) 越界；
        // 先 canonical 化再判包含性，越界返回 null，让上层照常回 404 而不区分探测结果。
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path
        val inside = file.path == rootPath || file.path.startsWith(rootPath + File.separator)
        if (!inside) return null

        if (!file.exists() || !file.isFile) return null
        val mimeType = MimeTypeHelper.getMimeType(file.name)
        return FileMappingResult(mimeType, file.inputStream())
    }
}

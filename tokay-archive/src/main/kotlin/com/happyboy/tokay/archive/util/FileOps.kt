package com.happyboy.tokay.archive.util

import java.io.File
import java.io.IOException

/**
 * 文件与路径相关的工具方法。
 */
object FileOps {
    /**
     * 确保目录存在且为目录；若不存在则创建。
     * @throws IOException 当路径不可用或创建失败时
     */
    fun ensureDir(dir: File) {
        if (!dir.exists()) {
            if (!dir.mkdirs() && !dir.exists()) {
                throw IOException("Failed to create dir: ${dir.absolutePath}")
            }
        } else if (!dir.isDirectory) {
            throw IOException("Not a directory: ${dir.absolutePath}")
        }
    }

    /**
     * 在 Root 目录下安全解析条目名，防 Zip Slip：统一 Windows/Unix 分隔符、去除开头的 ./ 与 /、
     * 拒绝包含 ':' 的路径片段，并用 canonical 路径校验防止越权写入。
     * @throws SecurityException 当检测到可疑路径时
     */
    fun safeResolve(rootDir: File, entryName: String): File {
        var name = entryName.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        while (name.startsWith("/")) name = name.substring(1)
        if (name.contains(":")) {
            throw SecurityException("Entry contains ':' -> $entryName")
        }
        val dest = File(rootDir, name)
        val rootPath = rootDir.canonicalFile.toPath().normalize()
        val destPath = dest.canonicalFile.toPath().normalize()
        if (!destPath.startsWith(rootPath)) {
            throw SecurityException("Zip Slip attempt: $entryName")
        }
        return destPath.toFile()
    }
}

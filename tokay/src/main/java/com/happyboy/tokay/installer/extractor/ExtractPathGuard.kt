package com.happyboy.tokay.installer.extractor

import java.io.File

/**
 * 解压落盘的路径校验（防 Zip Slip）：先拒绝对路径/盘符路径，再用 canonical path 规范化后比对前缀，
 * 越界即抛 [SecurityException] 中断解压。canonical 解析会展开符号链接，故指向目录之外的软链同样被拒。
 */
internal object ExtractPathGuard {

    private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /** 把条目名解析为目标目录内的文件（入参原样、不做规范化）；越界抛异常。 */
    fun resolveInside(rootDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            throw SecurityException("归档条目为绝对路径，已拒绝: $entryName")
        }

        val root = rootDir.canonicalFile
        val target = File(root, normalized).canonicalFile
        val rootPath = root.path
        val targetPath = target.path
        val inside = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        if (!inside) {
            throw SecurityException("归档条目越出解压目录: $entryName")
        }
        return target
    }
}

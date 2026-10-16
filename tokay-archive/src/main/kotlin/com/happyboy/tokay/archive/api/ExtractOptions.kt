package com.happyboy.tokay.archive.api

import java.nio.charset.Charset

/**
 * 解压选项。
 * @property overwrite 覆盖策略（NEVER/ALWAYS）
 * @property bufferSize I/O 缓冲区大小（字节），影响读写效率与内存占用
 * @property charset Zip 目录名/文件名所用字符集（仅 Zip 有效）
 * @property preserveTimestamps 是否保留条目的最后修改时间（部分格式/平台可能不生效）
 */
enum class OverwriteMode { NEVER, ALWAYS }

/** 用于配置解压过程的行为。 */
data class ExtractOptions(
    val overwrite: OverwriteMode = OverwriteMode.ALWAYS,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE_BYTES,
    val charset: Charset = Charsets.UTF_8,
    val preserveTimestamps: Boolean = true
) {
    companion object {
        /** 默认缓冲区大小（128KiB）。 */
        const val DEFAULT_BUFFER_SIZE_BYTES: Int = 128 * 1024
    }
}

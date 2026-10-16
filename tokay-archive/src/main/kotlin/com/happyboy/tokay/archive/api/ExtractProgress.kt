package com.happyboy.tokay.archive.api

/**
 * 解压进度信息。
 * @property entriesProcessed 已处理的条目数量
 * @property totalEntries 总条目数（未知则为 null，例如 7z 流式遍历）
 * @property bytesProcessed 已处理字节数（跨条目累积）
 * @property currentEntry 当前处理的条目路径（可能为目录或文件；开始/结束阶段可能为 null）
 */
data class ExtractProgress(
    val entriesProcessed: Int,
    val totalEntries: Int?,
    val bytesProcessed: Long,
    val currentEntry: String?
)

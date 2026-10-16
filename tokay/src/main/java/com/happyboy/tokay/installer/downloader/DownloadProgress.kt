package com.happyboy.tokay.installer.downloader

/** 下载进度。progress 为 0.0-1.0；speedBps 单位为字节/秒，0 表示未知；estimatedRemainingMs 为 -1 表示未知。 */
data class DownloadProgress(
    val progress: Float,

    val downloadedBytes: Long,

    val totalBytes: Long,

    val speedBps: Long = 0L,

    val estimatedRemainingMs: Long = -1L
) {
    /** 进度百分比（0-100）。 */
    val progressPercent: Int
        get() = (progress * 100).toInt()
}

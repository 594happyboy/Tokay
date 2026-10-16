package com.happyboy.tokay.installer.extractor

/** 解压进度。progress 为 0.0-1.0；totalBytes 为 -1L、currentFileName 为空串表示源头未给出。 */
data class ExtractProgress(
    val progress: Float,

    val extractedBytes: Long,

    val totalBytes: Long,

    val currentFileName: String = ""
) {
    /** 进度百分比（0-100）。 */
    val progressPercent: Int
        get() = (progress * 100).toInt()
}

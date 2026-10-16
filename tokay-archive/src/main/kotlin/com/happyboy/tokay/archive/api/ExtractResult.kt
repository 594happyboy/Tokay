package com.happyboy.tokay.archive.api

/**
 * 解压结果。
 * @property errors 错误列表（含致命错误与条目级别错误）
 * @property durationMs 总耗时（毫秒）
 */
data class ExtractResult(
    val successCount: Int,
    val failureCount: Int,
    val errors: List<ExtractError>,
    val canceled: Boolean,
    val durationMs: Long
)

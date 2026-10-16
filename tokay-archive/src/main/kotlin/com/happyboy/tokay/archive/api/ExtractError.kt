package com.happyboy.tokay.archive.api

/**
 * 解压过程中的错误类型。
 */
sealed class ExtractError(open val message: String, open val cause: Throwable? = null) {
    /** 不支持的压缩包类型。 */
    data class Unsupported(val path: String, val typeHint: String? = null): ExtractError(
        message = "Unsupported archive type for: $path"
    )
    /** 安全违规（如 Zip Slip、符号链接等被拒绝的场景）。 */
    data class SecurityViolation(val entry: String, override val cause: Throwable? = null): ExtractError(
        message = "Blocked unsafe entry: $entry",
        cause = cause
    )
    /** I/O 错误（读/写失败、磁盘空间不足等）。 */
    data class Io(val entry: String?, override val cause: Throwable): ExtractError(
        message = "I/O error for entry: ${entry ?: "<unknown>"}: ${cause.message}",
        cause = cause
    )
    /** 压缩包损坏或格式异常。 */
    data class Corrupted(val path: String, override val cause: Throwable? = null): ExtractError(
        message = "Corrupted archive: $path",
        cause = cause
    )
    /** 解压被取消。 */
    class Canceled: ExtractError(message = "Extraction canceled")
}

package com.happyboy.tokay.archive

import com.happyboy.tokay.archive.api.*
import com.happyboy.tokay.archive.impl.SevenZExtractor
import com.happyboy.tokay.archive.impl.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * 解压门面：统一入口与类型识别。
 */
object SevenZip {

    /**
     * 解压任意支持的压缩包（自动识别 zip / 7z）：在 IO 调度器中运行，支持取消与进度回调；
     * 7z 仅支持通过文件路径读取。
     */
    suspend fun extract(
        source: File,
        targetDir: File,
        options: ExtractOptions = ExtractOptions(),
        onProgress: (ExtractProgress) -> Unit = {}
    ): ExtractResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val result = when (detectType(source)) {
            ArchiveType.ZIP -> ZipExtractor.extract(source, targetDir, options, onProgress)
            ArchiveType.SEVEN_Z -> SevenZExtractor.extract(source, targetDir, options, onProgress)
            ArchiveType.UNKNOWN -> ExtractResult(
                successCount = 0,
                failureCount = 1,
                errors = listOf(ExtractError.Unsupported(source.absolutePath, null)),
                canceled = false,
                durationMs = 0
            )
        }
        result.copy(durationMs = (System.nanoTime() - start) / 1_000_000L)
    }

    /** 根据文件头魔数识别压缩包类型；无法识别返回 [ArchiveType.UNKNOWN]。 */
    fun detectType(file: File): ArchiveType {
        if (!file.isFile) return ArchiveType.UNKNOWN
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            val read = input.read(header)
            // 7z 魔数：37 7A BC AF 27 1C
            if (read >= 6 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte()
                && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte()
                && header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()
            ) return ArchiveType.SEVEN_Z
            // zip 本地文件头/数据描述符等常见签名：PK\x03\x04 / PK\x05\x06 / PK\x07\x08
            if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                && (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
                && (header[3] == 0x04.toByte() || header[3] == 0x06.toByte() || header[3] == 0x08.toByte())
            ) return ArchiveType.ZIP
        }
        return ArchiveType.UNKNOWN
    }
}
